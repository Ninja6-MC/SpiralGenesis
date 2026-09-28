#!/usr/bin/env python3
"""Fetch and reconcile one previously verified release candidate."""

import argparse
import hashlib
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from release_candidate import details, digest, read_json, verify


def command(*args, capture=True):
    return subprocess.run(args, check=True, text=True, capture_output=capture).stdout if capture else subprocess.run(args, check=True).returncode


def api(path):
    return json.loads(command("gh", "api", path))


def fetch(args):
    run = api(f"repos/{args.repository}/actions/runs/{args.run_id}")
    if any((run.get("name") != "Verify Release Candidate",
            run.get("path") != ".github/workflows/release-candidate.yml",
            run.get("event") != "workflow_dispatch", run.get("head_branch") != "main",
            run.get("conclusion") != "success", run.get("run_attempt") != 1)):
        raise ValueError("Candidate run is not a successful first-attempt main candidate")
    source = run["head_sha"]
    tag_source = command("git", "rev-parse", f"refs/tags/{args.tag}^{{commit}}").strip()
    remote_refs = command("git", "ls-remote", "origin", f"refs/tags/{args.tag}", f"refs/tags/{args.tag}^{{}}").splitlines()
    remote_source = remote_refs[-1].split()[0] if remote_refs else ""
    if source != tag_source or source != remote_source:
        raise ValueError("Tag does not resolve to candidate source on origin")
    artifacts = api(f"repos/{args.repository}/actions/runs/{args.run_id}/artifacts?per_page=100")["artifacts"]
    wanted = {"release-candidate-jar", "release-candidate-manifest", "release-candidate-evidence"}
    found = {item["name"]: item for item in artifacts if item["name"] in wanted and not item["expired"]}
    if len(found) != len(wanted) or len([item for item in artifacts if item["name"] in wanted]) != len(wanted):
        raise ValueError("Candidate artifacts are missing, expired or ambiguous")
    for name, target in (("release-candidate-jar", "candidate"),
                         ("release-candidate-manifest", "."),
                         ("release-candidate-evidence", ".")):
        command("gh", "run", "download", str(args.run_id), "-R", args.repository,
                "--name", name, "--dir", target, capture=False)
    manifest = read_json("candidate-manifest.json")
    if manifest["artifact_id"] != found["release-candidate-jar"]["id"]:
        raise ValueError("Downloaded JAR artifact ID differs from manifest")
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate",
                              tag=args.tag, source=source, run_id=args.run_id,
                              attempt=1, evidence="candidate-evidence.json"))


def recheck(args):
    manifest = read_json("candidate-manifest.json")
    source = command("git", "rev-parse", f"refs/tags/{args.tag}^{{commit}}").strip()
    remote_refs = command("git", "ls-remote", "origin", f"refs/tags/{args.tag}", f"refs/tags/{args.tag}^{{}}").splitlines()
    remote_source = remote_refs[-1].split()[0] if remote_refs else ""
    if source != remote_source:
        raise ValueError("Release tag moved on origin")
    verify(argparse.Namespace(manifest="candidate-manifest.json", directory="candidate",
                              tag=args.tag, source=source, run_id=manifest["run_id"],
                              attempt=1, evidence="candidate-evidence.json"))


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


def modrinth_versions(version, jar_path):
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        raise ValueError("MODRINTH_TOKEN is required for reconciliation")
    project = registry_json("https://api.modrinth.com/v2/project/spiralgenesis", token)
    if not project.get("id"):
        raise ValueError("Modrinth project identity cannot be established")
    versions = registry_json("https://api.modrinth.com/v2/project/spiralgenesis/version", token)
    if (not isinstance(versions, list)
            or any(not isinstance(item, dict) or not item.get("id") for item in versions)
            or len({item["id"] for item in versions}) != len(versions)):
        raise ValueError("Modrinth version inventory is invalid")
    # Aggregate routes omit drafts/unlisted versions even for members. A hash
    # lookup can recover an identical hidden candidate, but cannot prove that a
    # conflicting hidden version is absent. No match still requires confirmation.
    supported_hash = hashlib.sha512(Path(jar_path).read_bytes()).hexdigest()
    hidden = registry_json(f"https://api.modrinth.com/v2/version_file/{supported_hash}?algorithm=sha512",
                           token, missing=True)
    if (hidden and hidden.get("project_id") == project["id"] and hidden.get("version_number") == version
            and hidden.get("id") not in {item["id"] for item in versions}):
        versions.append(hidden)
    return versions


def hangar_version(version):
    key = os.environ.get("HANGAR_API_TOKEN")
    if not key:
        raise ValueError("HANGAR_API_TOKEN is required for reconciliation")
    session = registry_json("https://hangar.papermc.io/api/v1/authenticate?"
                            + urllib.parse.urlencode({"apiKey": key}), method="POST")
    token = session.get("token")
    if not isinstance(token, str) or not token:
        raise ValueError("Hangar authentication returned no session")
    authorization = "HangarAuth " + token
    permissions = registry_json("https://hangar.papermc.io/api/v1/permissions?project=SpiralGenesis", authorization)
    if "is_subject_member" not in permissions.get("permissions", []):
        raise ValueError("Hangar private-version visibility cannot be established")
    return registry_json(f"https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/{version}",
                         authorization, missing=True)


def destinations(args):
    recheck(args)
    manifest = read_json("candidate-manifest.json")
    version, channel = details(args.tag)
    jar_name = f"SpiralGenesis-{version}.jar"
    jar_hash = manifest["files"][jar_name]
    states = {}
    try:
        release = api(f"repos/{args.repository}/releases/tags/{args.tag}")
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in (error.stderr or ""):
            raise
        release = None
    if release:
        if release.get("target_commitish") != manifest["source"] or release.get("prerelease") != (channel != "release"):
            raise ValueError("Existing GitHub release metadata cannot be reconciled")
        assets = release.get("assets", [])
        if {asset["name"] for asset in assets} != set(manifest["files"]):
            raise ValueError("Existing GitHub release asset list differs")
        for name, expected_hash in manifest["files"].items():
            download = Path("existing-" + name)
            command("gh", "release", "download", args.tag, "-R", args.repository,
                    "--pattern", name, "--output", str(download), capture=False)
            if digest(download) != expected_hash:
                raise ValueError("Existing GitHub release asset bytes differ")
        states["github"] = "complete"
    else:
        states["github"] = "absent"
    versions = modrinth_versions(version, Path("candidate") / jar_name)
    if versions is None:
        raise ValueError("Modrinth project/version lookup unavailable")
    existing = [item for item in versions if item.get("version_number") == version]
    if len(existing) > 1:
        raise ValueError("Ambiguous Modrinth version")
    if existing:
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
        states["modrinth"] = "complete"
    else:
        if not getattr(args, "modrinth_absence_confirmed", False):
            raise ValueError("Modrinth absence is uncertain; confirm no private version in the dashboard before a new dispatch")
        states["modrinth"] = "absent"
    hangar = hangar_version(version)
    if hangar is not None:
        file_info = hangar.get("downloads", {}).get("PAPER", {}).get("fileInfo", {})
        expected_channel = "Alpha" if channel == "alpha" else "Beta" if channel == "beta" else "Release"
        if (hangar.get("name") != version or hangar.get("channel", {}).get("name") != expected_channel
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
        states["hangar"] = "complete"
    else:
        if not getattr(args, "hangar_absence_confirmed", False):
            raise ValueError("Hangar absence is uncertain; confirm no hidden or deleted version in the dashboard before a new dispatch")
        states["hangar"] = "absent"
    output = Path(args.output)
    with output.open("a", encoding="utf-8") as stream:
        for key, value in states.items():
            stream.write(f"{key}={value}\n")
        stream.write(f"channel={channel}\n")
        stream.write(f"hangar_channel={'Alpha' if channel == 'alpha' else 'Beta' if channel == 'beta' else 'Release'}\n")
        stream.write(f"version_json={json.dumps(version)}\n")


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
    for name in ("fetch", "recheck", "destinations", "notes", "prerelease"):
        item = sub.add_parser(name)
        item.add_argument("--tag", required=True)
        if name in ("fetch", "destinations"):
            item.add_argument("--repository", required=True)
        if name == "fetch":
            item.add_argument("--run-id", type=int, required=True)
        if name in ("destinations", "notes"):
            item.add_argument("--output", required=True)
        if name == "destinations":
            item.add_argument("--modrinth-absence-confirmed", action="store_true")
            item.add_argument("--hangar-absence-confirmed", action="store_true")
    args = parser.parse_args()
    try:
        if args.action == "fetch":
            fetch(args)
        elif args.action == "recheck":
            recheck(args)
        elif args.action == "destinations":
            destinations(args)
        elif args.action == "notes":
            notes(args)
        else:
            print("--prerelease" if details(args.tag)[1] != "release" else "")
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Release promotion stopped: {exc}\n")


if __name__ == "__main__":
    main()
