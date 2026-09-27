#!/usr/bin/env python3
"""Name a CI build from the nearest reachable release tag."""

import re
import subprocess


RELEASE_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[0-9]+)?\Z")


def git(*args):
    return subprocess.check_output(("git", *args), text=True).strip()


def snapshot_version():
    tags = [
        tag for tag in git("tag", "--merged", "HEAD", "--list", "v*").splitlines()
        if RELEASE_TAG.fullmatch(tag)
    ]
    if tags:
        described = git("describe", "--tags", "--long", *(f"--match={tag}" for tag in tags))
        return described.removeprefix("v")
    return f"0.0.0-SNAPSHOT-g{git('rev-parse', '--short', 'HEAD')}"


if __name__ == "__main__":
    print(snapshot_version())
