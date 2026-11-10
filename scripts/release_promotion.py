#!/usr/bin/env python3
"""Fetch, plan and reconcile one previously verified release candidate.

Publish Release runs one `plan` job with no environment and no publishing secrets, then
one job per destination behind the protected `release` environment. Each subcommand here
is one step of that sequence; RELEASE_PROCESS.md describes the whole.
"""

import argparse
import hashlib
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from release_candidate import DESTINATIONS, TAG, details, digest, read_json, verify


# Destinations whose absence cannot be proven from their API. Writing to one of them
# needs a per-dispatch absence attestation unless it is already complete.
ATTESTED = ("modrinth", "hangar")
LABELS = {"github": "GitHub", "modrinth": "Modrinth", "hangar": "Hangar"}
HANGAR_CHANNELS = {"alpha": "Alpha", "beta": "Beta", "release": "Release"}
CANDIDATE_WORKFLOW = ".github/workflows/release-candidate.yml"
# Every workflow able to write release versions. Each shares the publisher's concurrency
# group, and each counts when deciding whether an attestation is still current.
PUBLISHER_WORKFLOWS = ("release.yml",)
VERIFY_TRIES = 6
VERIFY_DELAY = 20


def command(*args, capture=True):
    return subprocess.run(args, check=True, text=True, capture_output=capture).stdout if capture else subprocess.run(args, check=True).returncode


def api(path):
    return json.loads(command("gh", "api", path))


def write_outputs(path, values):
    with Path(path).open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def tag_source(tag):
    """The tag's commit, once the local tag and origin agree on it."""
    source = command("git", "rev-parse", f"refs/tags/{tag}^{{commit}}").strip()
    remote_refs = command("git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}").splitlines()
    remote_source = remote_refs[-1].split()[0] if remote_refs else ""
    if source != remote_source:
        raise ValueError("Release tag moved on origin")
    return source


def check_candidate_run(run, repository):
    if any((run.get("name") != "Verify Release Candidate",
            run.get("path") != CANDIDATE_WORKFLOW,
            (run.get("repository") or {}).get("full_name") != repository,
            (run.get("head_repository") or {}).get("full_name") != repository,
            run.get("event") != "workflow_dispatch", run.get("head_branch") != "main",
            run.get("status") != "completed", run.get("conclusion") != "success",
            run.get("run_attempt") != 1)):
        raise ValueError("Candidate run is not a successful first-attempt main run of this repository's candidate workflow")


def fetch(args):
    run = api(f"repos/{args.repository}/actions/runs/{args.run_id}")
    check_candidate_run(run, args.repository)
    source = run["head_sha"]
    if tag_source(args.tag) != source:
        raise ValueError("Tag does not resolve to candidate source on origin")
    artifacts = api(f"repos/{args.repository}/actions/runs/{args.run_id}/artifacts?per_page=100")["artifacts"]
    wanted = {"release-candidate-jar", "release-candidate-manifest", "release-candidate-evidence"}
    found = {item["name"]: item for item in artifacts if item["name"] in wanted and not item["expired"]}
    if len(found) != len(wanted) or len([item for item in artifacts if item["name"] in wanted]) != len(wanted):
        raise ValueError("Candidate artifacts are missing, expired or ambiguous; an expired candidate is never rebuilt")
    for name, target in (("release-candidate-jar", "candidate"),
                         ("release-candidate-manifest", "."),
                         ("release-candidate-evidence", ".")):
        command("gh", "run", "download", str(args.run_id), "-R", args.repository,
                "--name", name, "--dir", target, capture=False)
    manifest = read_json("candidate-manifest.json")
    # The candidate workflow runs on main before the tag exists, so its tag input is
    # bound to this dispatch only through the manifest it recorded. Pin attempt 1.
    if (manifest.get("tag") != args.tag or manifest.get("source") != source
            or manifest.get("run_id") != args.run_id or manifest.get("attempt") != 1):
        raise ValueError("Candidate run was made for another tag, commit, run or attempt")
    if manifest["artifact_id"] != found["release-candidate-jar"]["id"]:
        raise ValueError("Downloaded JAR artifact ID differs from manifest")
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate",
                              tag=args.tag, source=source, run_id=args.run_id,
                              attempt=1, evidence="candidate-evidence.json"))


def recheck(args):
    manifest = read_json("candidate-manifest.json")
    source = tag_source(args.tag)
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate",
                              tag=args.tag, source=source, run_id=manifest["run_id"],
                              attempt=1, evidence="candidate-evidence.json"))


def select_destinations(text):
    names = [part.strip() for part in (text or "").split(",")]
    if not names or any(name not in DESTINATIONS for name in names) or len(set(names)) != len(names):
        raise ValueError(f"Destinations must be a comma-separated subset of {','.join(DESTINATIONS)}")
    return [name for name in DESTINATIONS if name in names]


def flag(value):
    return value is True or value == "true"


def registry_json(url, authorization=None, method="GET", missing=False):
    headers = {"User-Agent": "SpiralGenesis-release-check"}
    if authorization:
        headers["Authorization"] = authorization
    request = urllib.request.Request(url, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if missing and error.code == 404:
            return None
        # Authentication URLs contain the Hangar API key. Never render the URL,
        # response body or server-provided reason in a workflow log.
        raise ValueError(f"Registry request failed (HTTP {error.code})") from None
    except (urllib.error.URLError, OSError, ValueError):
        raise ValueError("Registry request unavailable or invalid") from None


def consumer_digest(url, host):
    if not isinstance(url, str) or not url.startswith(f"https://{host}/"):
        raise ValueError("Existing registry file has no trusted download URL")
    request = urllib.request.Request(url, headers={"User-Agent": "SpiralGenesis-release-check"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return hashlib.sha256(response.read()).hexdigest()
    except (urllib.error.URLError, OSError):
        raise ValueError("Registry file download unavailable") from None


def modrinth_inventory(versions):
    if (not isinstance(versions, list)
            or any(not isinstance(item, dict) or not item.get("id") for item in versions)
            or len({item["id"] for item in versions}) != len(versions)):
        raise ValueError("Modrinth version inventory is invalid")
    return versions


def modrinth_project(token=None):
    project = registry_json("https://api.modrinth.com/v2/project/spiralgenesis", token)
    if not isinstance(project, dict) or not project.get("id"):
        raise ValueError("Modrinth project identity cannot be established")
    if project.get("status") == "archived":
        raise ValueError("Modrinth project is archived and cannot receive versions")
    return project


def modrinth_versions(version, jar_path):
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        raise ValueError("MODRINTH_TOKEN is required for reconciliation")
    project = modrinth_project(token)
    versions = modrinth_inventory(registry_json("https://api.modrinth.com/v2/project/spiralgenesis/version", token))
    # Aggregate routes omit drafts/unlisted versions even for members. A hash
    # lookup can recover an identical hidden candidate, but cannot prove that a
    # conflicting hidden version is absent. No match still requires attestation.
    supported_hash = hashlib.sha512(Path(jar_path).read_bytes()).hexdigest()
    hidden = registry_json(f"https://api.modrinth.com/v2/version_file/{supported_hash}?algorithm=sha512",
                           token, missing=True)
    if (hidden and hidden.get("project_id") == project["id"] and hidden.get("version_number") == version
            and hidden.get("id") not in {item["id"] for item in versions}):
        versions.append(hidden)
    return versions


def hangar_session():
    key = os.environ.get("HANGAR_API_TOKEN")
    if not key:
        raise ValueError("HANGAR_API_TOKEN is required for reconciliation")
    session = registry_json("https://hangar.papermc.io/api/v1/authenticate?"
                            + urllib.parse.urlencode({"apiKey": key}), method="POST")
    token = session.get("token") if isinstance(session, dict) else None
    if not isinstance(token, str) or not token:
        raise ValueError("Hangar authentication returned no session")
    authorization = "HangarAuth " + token
    permissions = registry_json("https://hangar.papermc.io/api/v1/permissions?project=SpiralGenesis", authorization)
    granted = permissions.get("permissions", []) if isinstance(permissions, dict) else []
    if "is_subject_member" not in granted:
        raise ValueError("Hangar private-version visibility cannot be established")
    return authorization, granted


def hangar_version(version):
    authorization, _ = hangar_session()
    return registry_json(f"https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/{version}",
                         authorization, missing=True)


def candidate_identity(tag):
    manifest = read_json("candidate-manifest.json")
    version, channel = details(tag)
    jar_name = f"SpiralGenesis-{version}.jar"
    return manifest, version, channel, jar_name, manifest["files"][jar_name]


def github_state(repository, tag, manifest, channel):
    try:
        release = api(f"repos/{repository}/releases/tags/{tag}")
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in (error.stderr or ""):
            raise
        return "absent"
    if release.get("target_commitish") != manifest["source"] or release.get("prerelease") != (channel != "release"):
        raise ValueError("Existing GitHub release metadata cannot be reconciled")
    assets = release.get("assets", [])
    if {asset["name"] for asset in assets} != set(manifest["files"]):
        raise ValueError("Existing GitHub release asset list differs")
    for name, expected_hash in manifest["files"].items():
        download = Path("existing-" + name)
        if download.exists():
            download.unlink()
        command("gh", "release", "download", tag, "-R", repository,
                "--pattern", name, "--output", str(download), capture=False)
        if digest(download) != expected_hash:
            raise ValueError("Existing GitHub release asset bytes differ")
    return "complete"


def modrinth_state(versions, version, channel, jar_name, jar_hash):
    """`complete`, or None when no version with this number is visible."""
    existing = [item for item in versions if item.get("version_number") == version]
    if len(existing) > 1:
        raise ValueError("Ambiguous Modrinth version")
    if not existing:
        return None
    item = existing[0]
    files = item.get("files", [])
    if item.get("version_type") != channel or len(files) != 1 or files[0].get("filename") != jar_name:
        raise ValueError("Existing Modrinth version differs from candidate")
    if consumer_digest(files[0].get("url"), "cdn.modrinth.com") != jar_hash:
        raise ValueError("Existing Modrinth consumer JAR bytes differ")
    if item.get("status") != "listed":
        raise ValueError("Existing Modrinth version is nonpublic; reconcile its status manually")
    consumer = registry_json(f"https://api.modrinth.com/v2/version/{item['id']}")
    if not consumer or consumer.get("status") != "listed" or consumer.get("id") != item["id"]:
        raise ValueError("Existing Modrinth version is not publicly available")
    return "complete"


def hangar_state(hangar, version, channel, jar_name, jar_hash):
    """`complete`, or None when the lookup found no version."""
    if hangar is None:
        return None
    file_info = hangar.get("downloads", {}).get("PAPER", {}).get("fileInfo", {})
    if (hangar.get("name") != version or hangar.get("channel", {}).get("name") != HANGAR_CHANNELS[channel]
            or file_info.get("name") != jar_name or file_info.get("sha256Hash") != jar_hash):
        raise ValueError("Existing Hangar version metadata or JAR digest differs")
    url = hangar["downloads"]["PAPER"].get("downloadUrl")
    if consumer_digest(url, "hangarcdn.papermc.io") != jar_hash:
        raise ValueError("Existing Hangar consumer JAR bytes differ")
    if hangar.get("visibility") != "public":
        raise ValueError("Existing Hangar version is nonpublic; reconcile its visibility manually")
    consumer = registry_json(f"https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/{version}")
    if not consumer or consumer.get("visibility") != "public" or consumer.get("name") != version:
        raise ValueError("Existing Hangar version is not publicly available")
    return "complete"


def owner_state(destination, repository, tag):
    """State from the destination's most complete lookup: `complete`, `absent` or None (unproven)."""
    manifest, version, channel, jar_name, jar_hash = candidate_identity(tag)
    if destination == "github":
        return github_state(repository, tag, manifest, channel)
    if destination == "modrinth":
        return modrinth_state(modrinth_versions(version, Path("candidate") / jar_name), version, channel, jar_name, jar_hash)
    return hangar_state(hangar_version(version), version, channel, jar_name, jar_hash)


def public_state(destination, repository, tag):
    """State from lookups that need no publishing credential."""
    manifest, version, channel, jar_name, jar_hash = candidate_identity(tag)
    if destination == "github":
        return github_state(repository, tag, manifest, channel)
    if destination == "modrinth":
        versions = modrinth_inventory(registry_json("https://api.modrinth.com/v2/project/spiralgenesis/version"))
        return modrinth_state(versions, version, channel, jar_name, jar_hash)
    hangar = registry_json(f"https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/{version}", missing=True)
    return hangar_state(hangar, version, channel, jar_name, jar_hash)


def missing_attestation(destination, version):
    return (f"{LABELS[destination]} absence is uncertain for {version}; inspect the owner inventory, "
            f"including draft, scheduled, unlisted, hidden and deleted versions, then dispatch again "
            f"with {destination}_absence_confirmed=true")


def publisher_runs(repository):
    runs = []
    for workflow in PUBLISHER_WORKFLOWS:
        lines = command("gh", "api", "--paginate", f"repos/{repository}/actions/workflows/{workflow}/runs?per_page=100",
                        "--jq", ".workflow_runs[] | {id, status, head_branch, created_at, updated_at}")
        runs.extend(json.loads(line) for line in lines.splitlines() if line.strip())
    return runs


def conflicting_runs(runs, current, tag):
    """Publisher runs for this tag that may have written a version the attestation did not see."""
    conflicts = []
    for run in runs:
        if run.get("id") == current["id"] or run.get("head_branch") != tag:
            continue
        # A run created after this one shares its concurrency group, so it cannot start
        # while this run holds the group and cannot have written anything. That holds
        # whatever its status: one cancelled while queued for the group, because a newer
        # dispatch replaced it or by hand, is completed with a fresh updated_at.
        if run.get("created_at", "") > current["created_at"]:
            continue
        if run.get("status") != "completed":
            conflicts.append(run)
        # updated_at is never earlier than completion, so this errs towards refusing.
        elif run.get("updated_at", "") > current["created_at"]:
            conflicts.append(run)
    return conflicts


def check_attestation(repository, tag, run_id, attempt):
    """Refuse a true attestation on a later attempt, or when another publisher run may have written."""
    if attempt != 1:
        raise ValueError(f"A true absence attestation is refused on run attempt {attempt}; dispatch a new run")
    current = api(f"repos/{repository}/actions/runs/{run_id}")
    if current.get("head_branch") != tag:
        raise ValueError("Publisher run is not on the release tag ref")
    conflicts = conflicting_runs(publisher_runs(repository), current, tag)
    if conflicts:
        ids = ", ".join(str(run["id"]) for run in conflicts)
        raise ValueError(f"A true absence attestation is refused: publisher run {ids} for {tag} is in progress "
                         "or finished after this run was created; inspect the owner inventory again and dispatch a new run")


def guard(args):
    if flag(args.attested):
        check_attestation(args.repository, args.tag, args.run_id, args.attempt)
        print(f"{LABELS[args.destination]} absence attestation accepted for run {args.run_id} attempt {args.attempt}")
    recheck(args)


def version_key(tag):
    match = TAG.fullmatch(tag)
    rank = {"alpha": 0, "beta": 1, "rc": 2, None: 3}[match.group(4)]
    return tuple(int(match.group(i)) for i in (1, 2, 3)) + (rank, int(match.group(5) or 0))


def newest_on_channel(tag, public_tags):
    channel = details(tag)[1]
    same = [item for item in public_tags if TAG.fullmatch(item) and details(item)[1] == channel]
    return all(version_key(item) <= version_key(tag) for item in same)


def public_release_tags(repository):
    lines = command("gh", "api", "--paginate", f"repos/{repository}/releases?per_page=100",
                    "--jq", ".[] | select(.draft | not) | .tag_name")
    return [line.strip() for line in lines.splitlines() if line.strip()]


def project_checks(selected):
    """Checks that need no secret: each selected registry project resolves publicly."""
    if "modrinth" in selected:
        modrinth_project()
    if "hangar" in selected:
        project = registry_json("https://hangar.papermc.io/api/v1/projects/SpiralGenesis")
        if not isinstance(project, dict) or project.get("name") != "SpiralGenesis":
            raise ValueError("Hangar project identity cannot be established")


def plan(args):
    selected = select_destinations(args.destinations)
    attested = {name: flag(getattr(args, f"{name}_attested")) for name in ATTESTED}
    for name, value in attested.items():
        if value and name not in selected:
            raise ValueError(f"{LABELS[name]} absence attestation given, but {name} is not a selected destination")
    recheck(args)
    version, channel = details(args.tag)
    states = {name: public_state(name, args.repository, args.tag) for name in selected}
    for name in ATTESTED:
        if name in selected and states[name] != "complete" and not attested[name]:
            raise ValueError(missing_attestation(name, version))
    if any(attested.values()):
        check_attestation(args.repository, args.tag, args.run_id, args.attempt)
    project_checks(selected)
    newest = newest_on_channel(args.tag, public_release_tags(args.repository))
    outputs = {name: str(name in selected).lower() for name in DESTINATIONS}
    outputs.update({f"{name}_public": states.get(name) or "unproven" for name in DESTINATIONS if name in selected})
    outputs.update({"destinations": ",".join(selected), "channel": channel,
                    "hangar_channel": HANGAR_CHANNELS[channel], "version_json": json.dumps(version),
                    "newest": str(newest).lower()})
    write_outputs(args.output, outputs)


def preflight(args):
    """Credential checks that write nothing, for every destination selected in this dispatch."""
    selected = select_destinations(args.destinations)
    if "github" in selected:
        if not os.environ.get("GH_TOKEN"):
            raise ValueError("GH_TOKEN is required")
        api(f"repos/{args.repository}/releases?per_page=1")
    if "modrinth" in selected:
        token = os.environ.get("MODRINTH_TOKEN")
        if not token:
            raise ValueError("MODRINTH_TOKEN is required")
        modrinth_project(token)
        modrinth_inventory(registry_json("https://api.modrinth.com/v2/project/spiralgenesis/version", token))
    if "hangar" in selected:
        _, granted = hangar_session()
        needed = ["create_version"] + (["edit_page"] if flag(args.page_sync) else [])
        missing = [name for name in needed if name not in granted]
        if missing:
            raise ValueError(f"Hangar API key lacks {', '.join(missing)}")
    print(f"Credential preflight passed for {','.join(selected)}")


def reconcile(args):
    recheck(args)
    version = details(args.tag)[0]
    state = owner_state(args.destination, args.repository, args.tag)
    if state is None:
        if not flag(args.attested):
            raise ValueError(missing_attestation(args.destination, version))
        state = "absent"
    write_outputs(args.output, {"state": state})
    print(f"{LABELS[args.destination]}: {state}")


def verify_published(args):
    """After an upload, require the destination to be complete as consumers download it."""
    for attempt in range(1, VERIFY_TRIES + 1):
        try:
            if owner_state(args.destination, args.repository, args.tag) == "complete":
                print(f"{LABELS[args.destination]} serves the candidate bytes")
                return
            problem = "version not visible yet"
        except ValueError as error:
            problem = str(error)
        if attempt < VERIFY_TRIES:
            print(f"{LABELS[args.destination]} not verified yet ({problem}); retrying")
            time.sleep(VERIFY_DELAY)
    raise ValueError(f"{LABELS[args.destination]} upload could not be verified from consumer downloads: {problem}")


def notes(args):
    version, channel = details(args.tag)
    base = version.split("-")[0]
    lines = Path("CHANGELOG.md").read_text(encoding="utf-8").splitlines()
    for heading in (version, base):
        start = next((i + 1 for i, line in enumerate(lines) if line.startswith(f"## [{heading}]")), None)
        if start is not None:
            end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
            text = "\n".join(lines[start:end]).strip()
            if text:
                Path(args.output).write_text(text + "\n", encoding="utf-8")
                return
    if channel == "release":
        raise ValueError(f"Stable release has no changelog section for {base}")
    Path(args.output).write_text(f"SpiralGenesis {version} prerelease.\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("fetch", "recheck", "plan", "preflight", "reconcile", "guard", "verify-published", "notes", "prerelease"):
        item = sub.add_parser(name)
        item.add_argument("--tag", required=True)
        if name in ("fetch", "plan", "preflight", "reconcile", "guard", "verify-published"):
            item.add_argument("--repository", required=True)
        if name in ("fetch", "plan", "guard"):
            item.add_argument("--run-id", type=int, required=True)
        if name in ("plan", "guard"):
            item.add_argument("--attempt", type=int, required=True)
        if name in ("plan", "reconcile", "notes"):
            item.add_argument("--output", required=True)
        if name in ("plan", "preflight"):
            item.add_argument("--destinations", required=True)
        if name == "plan":
            item.add_argument("--modrinth-attested", choices=("true", "false"), default="false")
            item.add_argument("--hangar-attested", choices=("true", "false"), default="false")
        if name == "preflight":
            item.add_argument("--page-sync", choices=("true", "false"), default="false")
        if name in ("reconcile", "guard", "verify-published"):
            item.add_argument("--destination", choices=DESTINATIONS, required=True)
        if name in ("reconcile", "guard"):
            item.add_argument("--attested", choices=("true", "false"), default="false")
    args = parser.parse_args()
    handlers = {"fetch": fetch, "recheck": recheck, "plan": plan, "preflight": preflight,
                "reconcile": reconcile, "guard": guard, "verify-published": verify_published, "notes": notes}
    try:
        if args.action in handlers:
            handlers[args.action](args)
        else:
            print("--prerelease" if details(args.tag)[1] != "release" else "")
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Release promotion stopped: {exc}\n")


if __name__ == "__main__":
    main()
