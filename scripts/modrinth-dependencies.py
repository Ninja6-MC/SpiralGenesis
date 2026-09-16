#!/usr/bin/env python3
#
# Checks that the Modrinth dependencies declared in .github/workflows/release.yml match
# the plugin dependencies declared in src/main/resources/plugin.yml.
#
# Usage:
#   python scripts/modrinth-dependencies.py
#
# Why this exists: plugin.yml names dependencies by their Bukkit plugin name, and Modrinth
# identifies them by project id. mc-publish cannot map one to the other, so the release
# workflow pins the ids by hand - and a hand-kept list drifts. MODRINTH below is the one
# place the mapping lives; this script fails when either file disagrees with it.
#
# Hand-parsed rather than read with PyYAML, which is not guaranteed on a runner. Both
# parsers accept only the shapes these files use and fail on anything else, so a format
# change is reported rather than read as "no dependencies".

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLUGIN_YML = os.path.join(ROOT, "src", "main", "resources", "plugin.yml")
RELEASE_YML = os.path.join(ROOT, ".github", "workflows", "release.yml")

# Bukkit plugin name, exactly as plugin.yml spells it -> (mc-publish dependency name,
# Modrinth project id). Adding a dependency to plugin.yml means adding it here and to the
# publish step's `dependencies:` block.
MODRINTH = {
    "floodgate": ("floodgate", "bWrNNfkb"),
    "AuthMe": ("authmereloaded", "9js4IEHC"),
    "GriefPrevention": ("griefprevention", "O4o4mKaq"),
}

# plugin.yml key -> the mc-publish dependency kind it corresponds to.
KINDS = {"depend": "required", "softdepend": "optional"}

DEPENDENCY_LINE = re.compile(
    r"^([A-Za-z0-9_.-]+)\((required|optional|embedded|incompatible)\)\{modrinth:([A-Za-z0-9]+)\}$"
)


class FormatError(ValueError):
    pass


def plugin_dependencies(text):
    """Return {name: kind} from plugin.yml's top-level depend and softdepend lists."""
    lines = text.replace("\r\n", "\n").split("\n")
    found = {}
    for index, line in enumerate(lines):
        match = re.match(r"^(depend|softdepend):\s*(.*?)\s*(#.*)?$", line)
        if not match:
            continue
        key, inline = match.group(1), match.group(2)
        names = []
        if inline.startswith("[") and inline.endswith("]"):
            names = [n.strip().strip("'\"") for n in inline[1:-1].split(",") if n.strip()]
        elif inline:
            raise FormatError(
                "plugin.yml:%d: %s is not a list this check can read" % (index + 1, key)
            )
        else:
            for item in lines[index + 1:]:
                if item.strip() == "" or item.lstrip().startswith("#"):
                    continue
                entry = re.match(r"^\s+-\s+(\S+?)\s*(#.*)?$", item)
                if not entry:
                    if item[:1].isspace():
                        raise FormatError(
                            "plugin.yml: unreadable entry under %s: %s" % (key, item.strip())
                        )
                    break
                names.append(entry.group(1).strip("'\""))
        for name in names:
            if name in found:
                raise FormatError("plugin.yml: %s is declared more than once" % name)
            found[name] = KINDS[key]
    return found


def release_dependencies(text):
    """Return the entries of the publish step's `dependencies: |` block, as tuples."""
    lines = text.replace("\r\n", "\n").split("\n")
    blocks = [i for i, line in enumerate(lines) if re.match(r"^\s*dependencies:\s*\|\s*$", line)]
    if len(blocks) != 1:
        raise FormatError(
            "release.yml: expected one `dependencies: |` block, found %d" % len(blocks)
        )
    start = blocks[0]
    indent = len(lines[start]) - len(lines[start].lstrip())
    entries = []
    for number, line in enumerate(lines[start + 1:], start + 2):
        if line.strip() == "":
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        match = DEPENDENCY_LINE.match(line.strip())
        if not match:
            raise FormatError("release.yml:%d: unreadable dependency: %s" % (number, line.strip()))
        entries.append(match.groups())
    return entries


def compare(plugin, release):
    """Return every disagreement between plugin.yml, release.yml and MODRINTH."""
    problems = []
    expected = set()
    for name, kind in sorted(plugin.items()):
        if name not in MODRINTH:
            problems.append(
                "plugin.yml declares %s, which has no Modrinth id in MODRINTH "
                "(scripts/modrinth-dependencies.py)" % name
            )
            continue
        slug, project = MODRINTH[name]
        expected.add((slug, kind, project))
    for name in sorted(set(MODRINTH) - set(plugin)):
        problems.append(
            "MODRINTH maps %s, which plugin.yml no longer declares" % name
        )

    actual = set(release)
    if len(actual) != len(release):
        problems.append("release.yml lists the same dependency more than once")
    for slug, kind, project in sorted(expected - actual):
        problems.append(
            "release.yml is missing %s(%s){modrinth:%s}" % (slug, kind, project)
        )
    for slug, kind, project in sorted(actual - expected):
        problems.append(
            "release.yml lists %s(%s){modrinth:%s}, which plugin.yml does not declare"
            % (slug, kind, project)
        )
    return problems


def main():
    try:
        with io.open(PLUGIN_YML, encoding="utf-8") as handle:
            plugin = plugin_dependencies(handle.read())
        with io.open(RELEASE_YML, encoding="utf-8") as handle:
            release = release_dependencies(handle.read())
    except FormatError as error:
        sys.stderr.write("Cannot compare Modrinth dependencies:\n  %s\n" % error)
        sys.exit(1)

    problems = compare(plugin, release)
    if problems:
        sys.stderr.write("Modrinth dependencies are out of step with plugin.yml:\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        sys.exit(1)
    print("Modrinth dependencies match plugin.yml (%d)." % len(release))


if __name__ == "__main__":
    main()
