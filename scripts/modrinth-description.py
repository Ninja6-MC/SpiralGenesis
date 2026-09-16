#!/usr/bin/env python3
#
# Generates docs/modrinth-description.md from README.md.
#
# Usage:
#   python scripts/modrinth-description.py            # write the file
#   python scripts/modrinth-description.py --check    # fail if the file is out of date
#
# Why this exists: the Modrinth project body lives only on modrinth.com. Nothing in the
# release pipeline writes it - mc-publish uploads versions and a per-version changelog and
# has no description input - so the body was pasted in by hand when the project was created
# and then never touched again. By the time anyone looked it was three weeks stale, and
# Modrinth had rejected the submission over a markdown rule nobody could review, because
# the text existed in exactly one place and that place has no diff and no history.
#
# So the body is derived from the README instead, committed, and checked in CI. The README
# stays the single source of truth; this script removes the parts of it that only make
# sense on github.com and enforces the rule that got the submission rejected.
#
# This is not a markdown parser. It matches constructs line by line, and it handles one
# README that is edited deliberately. The contract is therefore narrow on purpose: a
# construct it does not know how to transform is refused with the README line that uses
# it (see unsupported_constructs), rather than passed through to a store page that is
# silently wrong. Widen the contract by teaching a transform the construct and removing
# the refusal, never by removing the refusal alone.
#
# What it does NOT do: upload anything. Publishing the result is a manual paste into the
# Modrinth editor (or a PATCH /v2/project/spiralgenesis), followed by "Resubmit for
# review" - an edit alone does not re-enter the moderation queue.

import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")
OUTPUT = os.path.join(ROOT, "docs", "modrinth-description.md")

BLOB = "https://github.com/Ninja6-MC/SpiralGenesis/blob/main/"

# An image has to resolve to the file itself. A blob URL serves GitHub's HTML viewer, so
# routing image targets through BLOB would produce a broken image rather than a 404, which
# is harder to notice.
RAW = "https://raw.githubusercontent.com/Ninja6-MC/SpiralGenesis/main/"

# Anything already addressable as-is from modrinth.com. The ONLY definition of
# absolute in this file: the transforms decide what to rewrite by it and the checks
# decide what to reject by it, so a target the transform leaves alone cannot then be
# rejected, and one it rewrites cannot slip past. Three divergent copies of this test
# previously meant a mailto link failed the build while a protocol-relative one was
# rewritten to blob/main///host and passed. Add a scheme here, not at a call site.
ABSOLUTE = re.compile(r"^(https?:|mailto:|#|//)", re.IGNORECASE)

# data: URIs are self-contained, so they resolve anywhere - but they are only acceptable
# where the content is embedded (an image source), not where it is navigated to (a link).
# Kept apart from ABSOLUTE so the one predicate below can say which is which.
DATA = re.compile(r"^data:", re.IGNORECASE)


def resolves(target, embedded):
    """Whether a target is usable as-is on modrinth.com.

    embedded is True for image sources (markdown images, src and srcset), where a data:
    URI is fine, and False for links (markdown links, href), where it is not.
    """
    return bool(ABSOLUTE.match(target)) or (embedded and bool(DATA.match(target)))


# A fence opens on three or more backticks or tildes, and closes only on a run of the
# same character at least as long, with nothing after it. Indentation is accepted on
# both, as it always has been here.
FENCE = re.compile(r"^\s*(`{3,}|~{3,})(.*)$")

# An inline code span: a backtick run, content, and a run of exactly the same length.
CODE_SPAN = re.compile(r"(?<!`)(`+)(?!`)(.+?)(?<!`)\1(?!`)")

# A raw HTML tag that opens and closes on one line.
TAG = re.compile(r"<(/?[A-Za-z][A-Za-z0-9-]*)([^<>]*)>")

# One attribute inside a tag: a name, optionally followed by a value in any of the three
# quoting styles.
ATTRIBUTE = re.compile(
    r"([^\s\"'>/=]+)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'=<>`]+)))?"
)
ATTRIBUTE_GAP = re.compile(r"[\s/]*")

# A markdown link or image, allowing one level of brackets in the text. Deliberately
# broader than the transforms' own patterns, so the post-condition check sees shapes the
# transforms do not.
ANY_LINK = re.compile(r"(!?)\[(?:[^\[\]]|\[[^\[\]]*\])*\]\(([^)]*)\)")

PROSE, DELIMITER, CODE = "prose", "delimiter", "code"


class ReadmeError(ValueError):
    """The README cannot be transformed at all, as opposed to producing a bad result.

    A distinct type so main() does not also swallow UnicodeDecodeError, which subclasses
    ValueError and would otherwise be reported as a malformed-README error with nothing
    but a codec message to go on.
    """

HEADER = (
    "<!-- Generated from README.md by scripts/modrinth-description.py. Do not edit.\n"
    "     The release workflow syncs everything below this comment to the Hangar resource page.\n"
    "     Paste the same text into the Modrinth description editor. -->\n"
)

# Prose characters the README uses that have no business in a store description we want to
# keep diffable and paste-safe. The box drawing in the spiral diagram is inside a fenced
# block and is deliberately left alone.
ASCII_FOLD = [
    (chr(0x2014), "-"),  # em dash (the README always spaces it)
    (chr(0x2013), "-"),  # en dash
    (chr(0x00b7), "-"),  # middle dot, used as a link separator
    (chr(0x00d7), "x"),  # multiplication sign, as in 500x500
    (chr(0x2705), "Yes"),  # check mark, compatibility table
    (chr(0x274c), "No"),  # cross mark, compatibility table
    (chr(0x2b07), ""),   # down arrow, download link
]


def fence_roles(lines):
    """Classify every line as PROSE, a fence DELIMITER, or CODE inside a fence.

    The single fence tracker for every transform and check, so that none of them can
    disagree about where a block starts. Both backtick and tilde fences are recognised,
    and a ``` line inside a ~~~ block is content rather than a closer.

    Returns (roles, unclosed), where unclosed is the index of the delimiter that opened
    a block the file never closes, or None.
    """
    roles = []
    opener = None
    opened_at = None
    for index, line in enumerate(lines):
        match = FENCE.match(line)
        if opener is None:
            # A backtick fence's info string cannot contain a backtick; such a line is
            # an inline code span, not a fence.
            if match and not (match.group(1)[0] == "`" and "`" in match.group(2)):
                opener = match.group(1)
                opened_at = index
                roles.append(DELIMITER)
            else:
                roles.append(PROSE)
        elif (
            match
            and match.group(1)[0] == opener[0]
            and len(match.group(1)) >= len(opener)
            and not match.group(2).strip()
        ):
            opener = None
            roles.append(DELIMITER)
        else:
            roles.append(CODE)
    return roles, (opened_at if opener is not None else None)


def mask_code_spans(line):
    """Replace inline code spans with placeholders, so nothing inside one is matched.

    Returns (masked, spans); unmask_code_spans puts them back. A code span renders
    literally on Modrinth, so a link or tag written inside one is an example, not a
    reference, and must be neither rewritten nor reported.
    """
    spans = []

    def stash(match):
        spans.append(match.group(0))
        return "\x00%d\x00" % (len(spans) - 1)

    return CODE_SPAN.sub(stash, line), spans


def unmask_code_spans(line, spans):
    return re.sub("\x00(\\d+)\x00", lambda match: spans[int(match.group(1))], line)


def map_prose(text, function):
    """Apply function to every prose line of text, with code spans masked."""
    lines = text.split("\n")
    roles, _ = fence_roles(lines)
    out = []
    for line, role in zip(lines, roles):
        if role == PROSE:
            masked, spans = mask_code_spans(line)
            line = unmask_code_spans(function(masked), spans)
        out.append(line)
    return "\n".join(out)


def tag_attributes(body):
    """Split the attribute part of a TAG match into (name, value) pairs.

    Returns (attributes, well_formed). Attributes are only ever read from inside a tag,
    never from a whole line, so prose such as "Pass href=docs/x to the helper." is not
    mistaken for one. Names are matched whole, so data-src is never reported as src.
    well_formed is False when two attributes run together with no whitespace between
    them, or when the body does not tokenise at all: a browser still reads such an
    attribute, so a check that skipped it would let a relative src through.
    """
    attributes = []
    position = 0
    while True:
        gap = ATTRIBUTE_GAP.match(body, position).end()
        if gap == len(body):
            return attributes, True
        if gap == position and position > 0:
            return attributes, False
        match = ATTRIBUTE.match(body, gap)
        if not match:
            return attributes, False
        value = match.group(2) or match.group(3) or match.group(4) or ""
        attributes.append((match.group(1).lower(), value))
        position = match.end()


def unsupported_constructs(lines):
    """Refuse markdown the transforms do not handle, naming the README line.

    Every entry here was a path to a wrong store page with no error: the transforms key
    on shapes they recognise and pass anything else through untouched. Refusing is the
    contract this generator can actually keep. Runs over README.md as read, so the line
    numbers point at the file a person edits.
    """
    problems = []
    roles, unclosed = fence_roles(lines)

    def report(number, message):
        problems.append("README.md:%d: %s" % (number, message))

    for number, (line, role) in enumerate(zip(lines, roles), 1):
        if role != PROSE:
            continue
        masked, _ = mask_code_spans(line)

        if "`" in masked:
            report(number, "unmatched backtick; a code span must open and close on one line")

        if re.match(r"^ {0,3}\[[^\]]+\]:", masked):
            report(number, "reference definition; use an inline link instead")
        if re.search(r"\]\[[^\]]*\]", masked):
            report(number, "reference-style link or image; use an inline link instead")

        if re.search(r"!\[[^\]]*\[", masked):
            report(number, "image alt text containing brackets")
        if re.search(r"\]\(\s", masked):
            report(number, "link target starting with whitespace")
        if re.search(r"\]\(<", masked):
            report(number, "angle-bracket link target; write the target bare")

        if re.search(r"<[A-Za-z][A-Za-z0-9-]*(\s[^<>]*)?$", masked):
            report(number, "HTML tag not closed on the same line")
        for tag in TAG.finditer(masked):
            if not tag_attributes(tag.group(2))[1]:
                report(
                    number,
                    "HTML attributes not separated by whitespace: %s" % tag.group(0),
                )

    if unclosed is not None:
        report(unclosed + 1, "code fence opened here is never closed")

    return problems


def strip_html_blocks(lines):
    """Drop the GitHub-only chrome: centred logo, H1, badge row, footer org mark.

    Modrinth renders the project title and icon itself, and none of the relative image
    sources resolve there. Anything at the top level that opens an HTML block goes, along
    with everything up to its closing tag.
    """
    out = []
    closing = None
    for line in lines:
        if closing is not None:
            if closing in line:
                closing = None
            continue
        stripped = line.strip()
        if stripped.startswith("<p ") or stripped.startswith("<p>"):
            if "</p>" not in stripped:
                closing = "</p>"
            continue
        if stripped.startswith("<h1"):
            continue
        out.append(line)

    # An unclosed block would otherwise consume every remaining line and still exit 0,
    # writing a description containing nothing but the generated-by comment. The tool
    # exists to stop a bad page reaching the store, so it must not fail open.
    if closing is not None:
        raise ReadmeError(
            "README.md has an HTML block that is never closed with %s. "
            "Stripping it would drop the rest of the file." % closing
        )

    return out


def strip_store_links(lines):
    """Drop the Download / Modrinth / Hangar row.

    A Modrinth page linking to itself is noise, and the download link duplicates the
    Versions tab sitting directly above the description.
    """
    out = []
    skipping = False
    for line in lines:
        if line.startswith("**[") and "Download]" in line:
            skipping = True
            continue
        if skipping:
            if line.strip() == "":
                skipping = False
            continue
        out.append(line)
    return out


def promote_headings(lines):
    """Promote H3 to H2.

    The README nests "What it does not do" under "What it does". With the README's H1
    gone, H2 is the top level of the description, and the nesting reads as a skipped
    level to a screen reader.
    """
    roles, _ = fence_roles(lines)
    return [
        "#" + line[2:] if role == PROSE and line.startswith("### ") else line
        for line, role in zip(lines, roles)
    ]


def fold_ascii(lines):
    """Fold typographic characters to ASCII, outside fenced blocks."""
    roles, _ = fence_roles(lines)
    out = []
    for line, role in zip(lines, roles):
        if role == PROSE:
            for bad, good in ASCII_FOLD:
                line = line.replace(bad, good)
        out.append(line)
    return out


def collapse_blanks(lines):
    out = []
    for line in lines:
        if line.strip() == "" and out and out[-1].strip() == "":
            continue
        out.append(line)
    while out and out[0].strip() == "":
        out.pop(0)
    while out and out[-1].strip() in ("", "---"):
        out.pop()
    return out


def count_sections(lines):
    """Headings the description keeps as sections: H2, and H3 once promoted."""
    roles, _ = fence_roles(lines)
    return sum(
        1
        for line, role in zip(lines, roles)
        if role == PROSE and re.match(r"^#{2,3} \S", line)
    )


def check_no_stray_hashes(lines):
    """The rule the submission was rejected over.

    Modrinth content rules 2.2: headers separate sections, they are not body text. A
    line-initial "#" inside a fenced block is YAML comment syntax, but read as raw
    markdown it is indistinguishable from a header used as body text - which is how the
    config block in this README got the project rejected on 2026-09-05.
    """
    problems = []
    roles, _ = fence_roles(lines)
    for number, (line, role) in enumerate(zip(lines, roles), 1):
        if role == DELIMITER or not line.lstrip().startswith("#"):
            continue
        if role == CODE:
            problems.append("%d: '#' begins a line inside a code fence: %s" % (number, line.strip()))
        elif not re.match(r"^#{2,6} \S", line):
            problems.append("%d: not a well-formed heading: %s" % (number, line.strip()))
    return problems


def check_no_relative_links(text):
    """Assert on the output that every markdown link and image target resolves.

    Not a re-run of the transforms: it matches with ANY_LINK, which accepts nested
    brackets and empty targets that the transform patterns do not, and it holds images
    and links to different standards. An image must not land on a blob URL (that serves
    an HTML page, not the image), and a link must not be a data: URI.
    """
    problems = []
    lines = text.split("\n")
    roles, _ = fence_roles(lines)
    for number, (line, role) in enumerate(zip(lines, roles), 1):
        if role != PROSE:
            continue
        masked, _ = mask_code_spans(line)
        for match in ANY_LINK.finditer(masked):
            image = match.group(1) == "!"
            target = match.group(2).strip()
            kind = "image" if image else "link"
            if not target:
                problems.append("%d: empty %s target" % (number, kind))
            elif not resolves(target, embedded=image):
                if DATA.match(target):
                    problems.append("%d: data: URI as a link target: %s" % (number, target[:40]))
                else:
                    problems.append("%d: relative %s: %s" % (number, kind, target))
            elif image and target.startswith(BLOB):
                problems.append("%d: image points at a GitHub page, not the file: %s" % (number, target))
    return problems


def check_no_relative_html_refs(text):
    """Catch relative references the stripper does not know how to remove.

    strip_html_blocks only recognises the chrome this README actually uses, so a tag it
    has never seen - a <div> wrapper, a bare <img> - survives into the output with its
    src or href intact. A relative one 404s on modrinth.com exactly like a relative
    markdown link does, and the markdown link check cannot see it because it is not
    markdown. So assert on the output rather than trying to enumerate every tag.

    All three quoting styles are matched. Checking only double quotes would close the
    case that has actually appeared in this README and leave the general one open, which
    is the kind of half-fix that reads as covered in review.
    """
    problems = []
    lines = text.split("\n")
    roles, _ = fence_roles(lines)
    for number, (line, role) in enumerate(zip(lines, roles), 1):
        if role != PROSE:
            continue
        masked, _ = mask_code_spans(line)
        for tag in TAG.finditer(masked):
            attributes, well_formed = tag_attributes(tag.group(2))
            if not well_formed:
                problems.append(
                    "%d: HTML attributes not separated by whitespace: %s" % (number, tag.group(0))
                )
            for attribute, value in attributes:
                if attribute not in ("src", "href", "srcset"):
                    continue
                embedded = attribute != "href"
                # srcset carries a comma-separated candidate list, each entry a URL
                # followed by an optional descriptor. Every candidate has to resolve, so
                # checking the whole value as one URL would pass on the first entry alone.
                for candidate in value.split(",") if attribute == "srcset" else [value]:
                    target = candidate.strip().split(" ")[0]
                    if target and not resolves(target, embedded):
                        if DATA.match(target):
                            problems.append(
                                "%d: data: URI in %s: %s" % (number, attribute, target[:40])
                            )
                        else:
                            problems.append(
                                "%d: relative %s in raw HTML: %s" % (number, attribute, target)
                            )
    return problems


def check_body_is_intact(text, expected):
    """A last sanity check that the sections actually survived the transforms.

    Cheap insurance against a stripper bug quietly dropping content. Stripping only ever
    removes chrome, never a section, so the description must carry at least as many
    sections as the README has: one survivor out of eleven is as wrong as none.
    """
    found = len(re.findall(r"(?m)^## \S", text))
    if found == 0:
        return ["generated description contains no section headings at all"]
    if found < expected:
        return [
            "generated description has %d section headings; README.md has %d"
            % (found, expected)
        ]
    return []


def check_ascii(text):
    problems = []
    lines = text.split("\n")
    roles, _ = fence_roles(lines)
    for number, (line, role) in enumerate(zip(lines, roles), 1):
        if role != PROSE:
            continue
        for character in line:
            if ord(character) > 127:
                problems.append("%d: non-ASCII U+%04X outside a code fence" % (number, ord(character)))
                break
    return problems


def absolutise_image_targets(text):
    """Point relative markdown image targets at raw.githubusercontent.com.

    Runs before absolutise_links, which would otherwise rewrite them through BLOB and
    produce an image tag pointing at an HTML page. The README carries no markdown images
    today; this exists so that adding one does not quietly break the store page. Fenced
    blocks and code spans are left alone: a link there is an example, not a reference.
    """

    def replace(match):
        target = match.group(2)
        if resolves(target, embedded=True):
            return match.group(0)
        return "!" + match.group(1) + "(" + RAW + target + ")"

    return map_prose(text, lambda line: re.sub(r"!(\[[^\]]*\])\(([^)]+)\)", replace, line))


def absolutise_links(text):
    """Point relative markdown links at github.com.

    On modrinth.com a relative target resolves against the project URL and 404s. A data:
    target is left for check_no_relative_links to reject rather than rewritten into a
    URL that would hide it.
    """

    def replace(match):
        target = match.group(1)
        if ABSOLUTE.match(target) or DATA.match(target):
            return match.group(0)
        return "](" + BLOB + target + ")"

    return map_prose(text, lambda line: re.sub(r"\]\(([^)]+)\)", replace, line))


def generate(source):
    """Transform README text into the description body, or raise / report why not.

    Returns (text, problems). Raises ReadmeError when the README uses something the
    generator refuses to transform.
    """
    lines = source.replace("\r\n", "\n").split("\n")

    unsupported = unsupported_constructs(lines)
    if unsupported:
        raise ReadmeError(
            "README.md uses markdown this generator does not handle:\n  "
            + "\n  ".join(unsupported)
        )

    sections = count_sections(lines)

    lines = strip_html_blocks(lines)
    lines = strip_store_links(lines)
    lines = promote_headings(lines)
    lines = fold_ascii(lines)
    lines = collapse_blanks(lines)

    text = absolutise_links(absolutise_image_targets("\n".join(lines))) + "\n"

    problems = (
        check_no_stray_hashes(text.split("\n"))
        + check_no_relative_links(text)
        + check_no_relative_html_refs(text)
        + check_ascii(text)
        + check_body_is_intact(text, sections)
    )
    return text, problems


def render():
    with io.open(README, encoding="utf-8") as handle:
        source = handle.read()

    text, problems = generate(source)
    if problems:
        sys.stderr.write("Generated description violates the Modrinth content rules:\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        sys.exit(1)

    return HEADER + "\n" + text


def main():
    parser = argparse.ArgumentParser(
        description="Generate docs/modrinth-description.md from README.md."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if docs/modrinth-description.md is out of date",
    )
    args = parser.parse_args()

    # A malformed README is a content error, not a crash: report it the same way a
    # rule violation is reported so CI logs read the same for both.
    try:
        generated = render()
    except ReadmeError as error:
        sys.stderr.write("Cannot generate the description from README.md:" + chr(10))
        sys.stderr.write("  " + str(error) + chr(10))
        sys.exit(1)

    if args.check:
        if not os.path.exists(OUTPUT):
            sys.stderr.write(
                "docs/modrinth-description.md does not exist.\n"
                "Run: python scripts/modrinth-description.py\n"
            )
            sys.exit(1)
        with io.open(OUTPUT, encoding="utf-8") as handle:
            current = handle.read().replace("\r\n", "\n")
        if current != generated:
            sys.stderr.write(
                "docs/modrinth-description.md is out of date with README.md.\n"
                "Run: python scripts/modrinth-description.py\n"
            )
            sys.exit(1)
        print("docs/modrinth-description.md is up to date.")
        return

    with io.open(OUTPUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(generated)
    print("Wrote docs/modrinth-description.md (%d characters)." % len(generated))


if __name__ == "__main__":
    main()
