#!/usr/bin/env python3
"""Validate retained release candidates and bind smoke-test evidence to their bytes."""

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


TAG = re.compile(r"^v([0-9]+)\.([0-9]+)\.([0-9]+)(?:-(alpha|beta|rc)\.([0-9]+))?$")
SHA = re.compile(r"^[0-9a-f]{40}$")
DESTINATIONS = ["github", "modrinth", "hangar"]
TESTS = ["gradle-test"] + [f"{platform}-{version}-smoke" for version in ("1.20.4", "1.21.11", "26.2") for platform in ("paper", "folia")] + ["paper-26.3-smoke"]


def details(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Invalid release tag: {tag}")
    version = tag[1:]
    tier = match.group(4)
    return version, "alpha" if tier == "alpha" else "beta" if tier else "release"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def create(args):
    version, channel = details(args.tag)
    if not SHA.fullmatch(args.source):
        raise ValueError("Source must be a full commit SHA")
    jar = Path(args.jar)
    if jar.name != f"SpiralGenesis-{version}.jar":
        raise ValueError("Candidate JAR filename does not match version")
    if not jar.is_file() or args.artifact_id < 1 or args.run_id < 1 or args.attempt < 1:
        raise ValueError("Missing JAR or invalid run/artifact identity")
    checksum = jar.with_name(jar.name + ".sha256")
    jar_hash = digest(jar)
    if not checksum.is_file() or checksum.read_text(encoding="utf-8") != f"{jar_hash}  {jar.name}\n":
        raise ValueError("Candidate checksum sidecar differs from JAR")
    data = {
        "schema": 1,
        "candidate_id": f"{args.run_id}-{args.attempt}-{args.source}-{args.tag}",
        "run_id": args.run_id,
        "attempt": args.attempt,
        "artifact_id": args.artifact_id,
        "source": args.source,
        "tag": args.tag,
        "version": version,
        "channel": channel,
        "destinations": DESTINATIONS,
        "files": {jar.name: jar_hash, checksum.name: digest(checksum)},
    }
    write_json(args.output, data)


def verify(args):
    data = read_json(args.manifest)
    version, channel = details(args.tag)
    expected_id = f"{args.run_id}-{args.attempt}-{args.source}-{args.tag}"
    if not SHA.fullmatch(args.source) or data.get("schema") != 1 or any(
        data.get(key) != value for key, value in {
            "candidate_id": expected_id, "run_id": args.run_id,
            "attempt": args.attempt, "source": args.source, "tag": args.tag,
            "version": version, "channel": channel, "destinations": DESTINATIONS,
        }.items()
    ):
        raise ValueError("Candidate identity, source, version, channel or destinations differ")
    if not isinstance(data.get("artifact_id"), int) or data["artifact_id"] < 1:
        raise ValueError("Missing artifact ID")
    expected_file = f"SpiralGenesis-{version}.jar"
    checksum_file = expected_file + ".sha256"
    files = data.get("files")
    if not isinstance(files, dict) or set(files) != {expected_file, checksum_file}:
        raise ValueError("Candidate manifest must name the versioned JAR and checksum")
    directory = Path(args.directory)
    actual = {p.name for p in directory.iterdir()}
    if actual != set(files):
        raise ValueError(f"Candidate file inventory differs: {actual}")
    jar = directory / expected_file
    for name, expected_hash in files.items():
        if digest(directory / name) != expected_hash:
            raise ValueError(f"Candidate {name} SHA-256 differs from manifest")
    if (directory / checksum_file).read_text(encoding="utf-8") != f"{files[expected_file]}  {expected_file}\n":
        raise ValueError("Candidate checksum sidecar differs from JAR")
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        if "plugin.yml" not in names or "com/ninja6/spiralgenesis/SpiralGenesisPlugin.class" not in names:
            raise ValueError("Candidate JAR lacks plugin descriptor or main class")
        descriptor = archive.read("plugin.yml").decode("utf-8")
        if not re.search(rf"(?m)^version:\s*['\"]?{re.escape(version)}['\"]?\s*$", descriptor):
            raise ValueError("Embedded plugin version differs")
        if not re.search(r"(?m)^name:\s*['\"]?SpiralGenesis['\"]?\s*$", descriptor):
            raise ValueError("Embedded plugin name differs")
    if args.evidence:
        evidence = read_json(args.evidence)
        if evidence != {
            "schema": 1, "candidate_id": expected_id, "manifest_sha256": digest(Path(args.manifest)),
            "artifact_id": data["artifact_id"], "files": files,
            "tests": TESTS, "result": "passed",
        }:
            raise ValueError("Missing or mismatched passing test evidence")
    print(f"Verified {expected_id}: {expected_file} {files[expected_file]}")


def evidence(args):
    data = read_json(args.manifest)
    verify(argparse.Namespace(manifest=args.manifest, directory=args.directory,
                              tag=data["tag"], source=data["source"], run_id=data["run_id"],
                              attempt=data["attempt"], evidence=None))
    write_json(args.output, {
        "schema": 1, "candidate_id": data["candidate_id"],
        "manifest_sha256": digest(Path(args.manifest)), "artifact_id": data["artifact_id"],
        "files": data["files"], "tests": TESTS,
        "result": "passed",
    })


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("create", "verify", "evidence"):
        command = sub.add_parser(name)
        command.add_argument("--manifest", default="candidate-manifest.json")
        command.add_argument("--directory", default="candidate")
        command.add_argument("--output")
        if name == "create":
            command.add_argument("--tag", required=True)
            command.add_argument("--source", required=True)
            command.add_argument("--run-id", type=int, required=True)
            command.add_argument("--attempt", type=int, required=True)
            command.add_argument("--artifact-id", type=int, required=True)
            command.add_argument("--jar", required=True)
        if name == "verify":
            command.add_argument("--tag", required=True)
            command.add_argument("--source", required=True)
            command.add_argument("--run-id", type=int, required=True)
            command.add_argument("--attempt", type=int, required=True)
            command.add_argument("--evidence")
    args = parser.parse_args()
    try:
        if args.command == "create":
            create(args)
        elif args.command == "verify":
            verify(args)
        else:
            evidence(args)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as exc:
        parser.exit(1, f"Release candidate rejected: {exc}\n")


if __name__ == "__main__":
    main()
