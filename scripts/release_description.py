#!/usr/bin/env python3
"""Compare the public Modrinth body with the description at its current owner tag."""

import argparse
import difflib
import os
import subprocess
from pathlib import Path

from release_candidate import TAG
from release_promotion import (command, public_owner, public_state, recheck,
                               registry_json, select_destinations, tag_source, write_outputs)

PROJECT = "https://api.modrinth.com/v2/project/spiralgenesis"
DESCRIPTION = "docs/modrinth-description.md"


def normalize(text):
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")).rstrip("\n")


def source_body(repository, tag):
    if not TAG.fullmatch(tag):
        raise ValueError(f"Description owner is not a supported release tag: {tag}")
    tag_source(tag)
    text = command("git", "show", f"refs/tags/{tag}:{DESCRIPTION}")
    if not text.startswith("<!--") or "-->" not in text:
        raise ValueError(f"{DESCRIPTION} at {tag} lacks its generated comment")
    # Match the resourcePage provider in build.gradle.kts.
    return text.split("-->", 1)[1].strip()


def public_body():
    project = registry_json(PROJECT)
    if not isinstance(project, dict) or not isinstance(project.get("body"), str):
        raise ValueError("Modrinth public project body is unavailable")
    return project["body"]


def file_link(repository, tag):
    return f"https://github.com/{repository}/blob/{tag}/{DESCRIPTION}"


def summary(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with Path(path).open("a", encoding="utf-8") as stream:
            stream.write(text + "\n\n")


def compare(expected, actual):
    return normalize(expected) == normalize(actual)


def difference(expected, actual):
    lines = list(difflib.unified_diff(normalize(expected).splitlines(), normalize(actual).splitlines(),
                                      fromfile="owner file", tofile="Modrinth body", n=1, lineterm=""))
    return "\n".join(line[:200] for line in lines[:16])


def check(args):
    recheck(args)
    selected = select_destinations(args.destinations)
    state = public_state("modrinth", args.repository, args.tag)
    if "modrinth" not in selected and state != "complete":
        summary(f"Skip Modrinth description: {args.tag} was not selected and is not complete.")
        write_outputs(args.output, {"paste_needed": "false"})
        return
    owner = public_owner("modrinth", args.repository)
    if owner != args.tag or state != "complete":
        summary(f"Skip Modrinth description for {args.tag}: page owner is {owner or 'none'}; public state is {state or 'absent'}.")
        write_outputs(args.output, {"paste_needed": "false"})
        return
    expected = source_body(args.repository, owner)
    needed = not compare(expected, public_body())
    write_outputs(args.output, {"paste_needed": str(needed).lower()})
    if needed:
        summary(f"## Modrinth description paste required\n\n"
                f"Paste [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}) into the "
                "[Modrinth description editor](https://modrinth.com/plugin/spiralgenesis/settings/description), "
                "omitting the leading generated comment, then approve the modrinth-description environment. "
                "A newer owner found at approval replaces this tag. Confirmation will name the file to paste; "
                "if it differs, paste that file and re-run the confirm job.")
    else:
        summary(f"Modrinth description already matches {owner}; no paste or approval needed.")


def confirm(args):
    # The manual paste is the write. This job only reads and must not hold the shared lock.
    for _ in range(3):
        owner = public_owner("modrinth", args.repository)
        if owner is None:
            raise ValueError("Modrinth has no public description owner")
        expected = source_body(args.repository, owner)
        actual = public_body()
        if public_owner("modrinth", args.repository) == owner:
            break
    else:
        raise ValueError("Modrinth description owner kept changing; re-run this confirm job")
    if owner != args.tag:
        summary(f"{args.tag} was superseded; Modrinth page owner is now {owner}.")
    if not compare(expected, actual):
        summary(f"Modrinth body differs from [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}). "
                "Paste that file without its leading generated comment and re-run this confirm job.\n\n"
                f"```diff\n{difference(expected, actual)}\n```")
        raise ValueError(f"Modrinth body does not match {owner}; paste {file_link(args.repository, owner)} and re-run this job")
    summary(f"Modrinth description matches the current owner {owner}.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("check", "confirm"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--destinations", default="github,modrinth,hangar")
    parser.add_argument("--output")
    args = parser.parse_args()
    if not TAG.fullmatch(args.tag) or (args.action == "check" and not args.output):
        parser.error("A valid release tag and, for check, --output are required")
    try:
        (check if args.action == "check" else confirm)(args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Modrinth description stopped: {exc}\n")


if __name__ == "__main__":
    main()
