#!/usr/bin/env python3
"""Fetch and reconcile one previously verified release candidate."""

import argparse
import hashlib
import json
import os
import subprocess
import urllib.error
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


def public_json(url):
    headers = {"User-Agent": "SpiralGenesis-release-check"}
    if url.startswith("https://api.modrinth.com/") and os.environ.get("MODRINTH_TOKEN"):
        headers["Authorization"] = os.environ["MODRINTH_TOKEN"]
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


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
    versions = public_json("https://api.modrinth.com/v2/project/spiralgenesis/version")
    if versions is None:
        raise ValueError("Modrinth project/version lookup unavailable")
    existing = [item for item in versions if item.get("version_number") == version]
    if len(existing) > 1:
        raise ValueError("Ambiguous Modrinth version")
    if existing:
        item = existing[0]
        files = item.get("files", [])
        if item.get("version_type") != channel or len(files) != 1 or files[0].get("filename") != jar_name or files[0].get("hashes", {}).get("sha256") != jar_hash:
            raise ValueError("Existing Modrinth version differs from candidate")
        states["modrinth"] = "complete"
    else:
        states["modrinth"] = "absent"
    hangar = public_json(f"https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/{version}")
    if hangar is not None:
        file_info = hangar.get("downloads", {}).get("PAPER", {}).get("fileInfo", {})
        expected_channel = "Alpha" if channel == "alpha" else "Beta" if channel == "beta" else "Release"
        if (hangar.get("name") != version or hangar.get("channel", {}).get("name") != expected_channel
                or file_info.get("name") != jar_name or file_info.get("sha256Hash") != jar_hash):
            raise ValueError("Existing Hangar version metadata or JAR digest differs")
        url = hangar["downloads"]["PAPER"].get("downloadUrl")
        if not url or not url.startswith("https://hangarcdn.papermc.io/"):
            raise ValueError("Existing Hangar version has no trusted download URL")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SpiralGenesis-release-check"}), timeout=30) as response:
            if hashlib.sha256(response.read()).hexdigest() != jar_hash:
                raise ValueError("Existing Hangar consumer JAR bytes differ")
        states["hangar"] = "complete"
    else:
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
