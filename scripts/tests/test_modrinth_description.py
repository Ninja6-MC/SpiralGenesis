"""Tests for scripts/modrinth-description.py.

Run: python -m unittest discover -s scripts/tests
"""

import importlib.util
import io
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "modrinth-description.py")

spec = importlib.util.spec_from_file_location("modrinth_description", SCRIPT)
md = importlib.util.module_from_spec(spec)
spec.loader.exec_module(md)

BLOB = md.BLOB
RAW = md.RAW


def readme(*body):
    """A minimal README with enough structure to generate from."""
    return "\n".join(["## Section", ""] + list(body) + [""])


def unsupported(*body):
    return md.unsupported_constructs(readme(*body).split("\n"))


class CommittedOutput(unittest.TestCase):
    def test_current_readme_generates_the_committed_file(self):
        with io.open(md.README, encoding="utf-8") as handle:
            text, problems = md.generate(handle.read())
        self.assertEqual(problems, [])
        with io.open(md.OUTPUT, encoding="utf-8") as handle:
            committed = handle.read().replace("\r\n", "\n")
        self.assertEqual(md.HEADER + "\n" + text, committed)


class Fences(unittest.TestCase):
    def test_tilde_fence_is_tracked(self):
        lines = ["~~~", "# comment", "~~~", "# bad"]
        problems = md.check_no_stray_hashes(lines)
        self.assertEqual(len(problems), 2)
        self.assertIn("inside a code fence", problems[0])
        self.assertIn("not a well-formed heading", problems[1])

    def test_non_ascii_inside_tilde_fence_is_allowed(self):
        self.assertEqual(md.check_ascii("~~~\n" + chr(0x2500) + "\n~~~\n"), [])

    def test_fold_ascii_skips_tilde_fence(self):
        dash = chr(0x2014)
        self.assertEqual(
            md.fold_ascii(["~~~", dash, "~~~", dash]), ["~~~", dash, "~~~", "-"]
        )

    def test_backtick_line_does_not_close_tilde_fence(self):
        roles, unclosed = md.fence_roles(["~~~", "```", "x", "~~~", "y"])
        self.assertEqual(roles, [md.DELIMITER, md.CODE, md.CODE, md.DELIMITER, md.PROSE])
        self.assertIsNone(unclosed)

    def test_shorter_run_does_not_close_longer_fence(self):
        roles, unclosed = md.fence_roles(["````", "```", "````"])
        self.assertEqual(roles, [md.DELIMITER, md.CODE, md.DELIMITER])

    def test_unclosed_fence_is_refused(self):
        problems = unsupported("```yaml", "a: b")
        self.assertEqual(len(problems), 1)
        self.assertIn("README.md:3: code fence opened here is never closed", problems[0])

    def test_links_inside_fences_are_not_rewritten(self):
        for fence in ("```", "~~~"):
            text = "%s\n[example](docs/A.md)\n![img](docs/x.png)\n%s\n" % (fence, fence)
            self.assertEqual(md.absolutise_links(md.absolutise_image_targets(text)), text)

    def test_links_inside_code_spans_are_not_rewritten(self):
        text = "Write `[example](docs/A.md)` to link.\n"
        self.assertEqual(md.absolutise_links(text), text)
        self.assertEqual(md.check_no_relative_links(text), [])


class Links(unittest.TestCase):
    def test_relative_link_goes_to_blob(self):
        self.assertEqual(md.absolutise_links("[a](docs/A.md)"), "[a](" + BLOB + "docs/A.md)")

    def test_relative_image_goes_to_raw(self):
        out = md.absolutise_links(md.absolutise_image_targets("![a](docs/x.png)"))
        self.assertEqual(out, "![a](" + RAW + "docs/x.png)")

    def test_absolute_targets_are_left_alone(self):
        for target in ("https://x", "mailto:a@b", "#anchor", "//host/x"):
            text = "[a](%s)" % target
            self.assertEqual(md.absolutise_links(text), text)
            self.assertEqual(md.check_no_relative_links(text), [])

    def test_image_alt_with_brackets_is_refused(self):
        problems = unsupported("![a [b] c](docs/x.png)")
        self.assertEqual(problems, ["README.md:3: image alt text containing brackets"])

    def test_image_on_blob_url_is_reported(self):
        problems = md.check_no_relative_links("![a [b] c](" + BLOB + "docs/x.png)")
        self.assertEqual(len(problems), 1)
        self.assertIn("image points at a GitHub page", problems[0])

    def test_reference_link_is_refused(self):
        self.assertEqual(
            unsupported("See [the guide][guide]."),
            ["README.md:3: reference-style link or image; use an inline link instead"],
        )

    def test_reference_image_is_refused(self):
        self.assertEqual(len(unsupported("![alt][ref]")), 1)

    def test_reference_definition_is_refused(self):
        self.assertEqual(
            unsupported("[ref]: docs/A.md"),
            ["README.md:3: reference definition; use an inline link instead"],
        )

    def test_reference_syntax_inside_fence_is_allowed(self):
        self.assertEqual(unsupported("```", "[ref]: docs/A.md", "![a][b]", "```"), [])

    def test_angle_bracket_target_is_refused(self):
        self.assertEqual(len(unsupported("[a](<docs/A.md>)")), 1)

    def test_unmatched_backtick_is_refused(self):
        self.assertEqual(len(unsupported("an `open span")), 1)

    def test_data_uri_image_is_allowed(self):
        text = "![a](data:image/png;base64,AAAA)"
        self.assertEqual(md.absolutise_image_targets(text), text)
        self.assertEqual(md.check_no_relative_links(text), [])

    def test_data_uri_link_is_rejected(self):
        text = md.absolutise_links("[click](data:text/html;base64,AAAA)")
        self.assertEqual(text, "[click](data:text/html;base64,AAAA)")
        problems = md.check_no_relative_links(text)
        self.assertEqual(len(problems), 1)
        self.assertIn("data: URI as a link target", problems[0])

    def test_empty_target_is_reported(self):
        self.assertIn("empty link target", md.check_no_relative_links("[a]()")[0])


class Html(unittest.TestCase):
    def test_relative_src_in_every_quoting_style(self):
        for tag in (
            '<img src="docs/x.png">',
            "<img src='docs/x.png'>",
            "<img src=docs/x.png>",
        ):
            problems = md.check_no_relative_html_refs(tag)
            self.assertEqual(problems, ["1: relative src in raw HTML: docs/x.png"], tag)

    def test_every_srcset_candidate_is_checked(self):
        problems = md.check_no_relative_html_refs(
            '<img srcset="https://x/a.png 1x, docs/b.png 2x">'
        )
        self.assertEqual(problems, ["1: relative srcset in raw HTML: docs/b.png"])

    def test_data_src_attribute_is_not_src(self):
        self.assertEqual(md.check_no_relative_html_refs('<img data-src="docs/x.png">'), [])

    def test_attribute_like_prose_is_not_reported(self):
        self.assertEqual(md.check_no_relative_html_refs("Pass href=docs/x to the helper."), [])
        self.assertEqual(unsupported("Pass href=docs/x to the helper."), [])

    def test_attribute_value_containing_equals_is_well_formed(self):
        self.assertEqual(unsupported('<img alt="a=b" src="https://x/y.png">'), [])

    def test_attributes_without_whitespace_are_refused(self):
        problems = unsupported('<img alt="a"src="docs/x.png">')
        self.assertEqual(len(problems), 1)
        self.assertIn("not separated by whitespace", problems[0])
        self.assertEqual(
            len(md.check_no_relative_html_refs('<img alt="a"src="docs/x.png">')), 1
        )

    def test_tag_split_across_lines_is_refused(self):
        self.assertEqual(
            unsupported('<img alt=""', 'src="docs/x.png">'),
            ["README.md:3: HTML tag not closed on the same line"],
        )

    def test_tag_inside_code_span_is_not_reported(self):
        self.assertEqual(md.check_no_relative_html_refs('Use `<img src="docs/x.png">`.'), [])

    def test_data_uri_allowed_for_src_not_href(self):
        self.assertEqual(md.check_no_relative_html_refs('<img src="data:image/svg+xml,x">'), [])
        problems = md.check_no_relative_html_refs('<a href="data:text/html,x">x</a>')
        self.assertEqual(len(problems), 1)
        self.assertIn("data: URI in href", problems[0])


class EscapesAndWrapping(unittest.TestCase):
    def test_escaped_bracket_in_image_alt_is_refused(self):
        self.assertEqual(
            unsupported(r"![a \] b](docs/x.png)"),
            ["README.md:3: backslash-escaped bracket; rephrase without it"],
        )

    def test_escaped_bracket_in_link_text_is_refused(self):
        self.assertEqual(len(unsupported(r"[a \[ b](docs/A.md)")), 1)

    def test_escaped_bracket_inside_code_span_is_allowed(self):
        self.assertEqual(unsupported(r"Write `\]` literally."), [])

    def test_title_on_next_line_is_refused(self):
        self.assertEqual(
            unsupported("[a](docs/A.md", '"title")'),
            ["README.md:3: link target not closed on the same line"],
        )

    def test_destination_on_next_line_is_refused(self):
        self.assertEqual(
            unsupported("[a](", "docs/A.md)"),
            ["README.md:3: link target not closed on the same line"],
        )

    def test_parentheses_inside_target_are_not_wrapping(self):
        self.assertEqual(unsupported("[w](https://en.wikipedia.org/wiki/A_(b))"), [])

    def test_wrapped_code_span_message_says_how_to_fix(self):
        problems = unsupported("run `./gradlew", "build` now")
        self.assertEqual(len(problems), 2)
        self.assertIn("rejoin the code span onto one line", problems[0])

    def test_fence_in_blockquote_is_reported_as_unsupported(self):
        problems = unsupported("> ```", "> [x](docs/A.md)", "> ```")
        self.assertEqual(
            problems,
            [
                "README.md:3: code fence inside a blockquote is not supported",
                "README.md:5: code fence inside a blockquote is not supported",
            ],
        )


class FencedChrome(unittest.TestCase):
    def test_fenced_paragraph_html_is_kept(self):
        lines = ["```html", '<p align="center">', "x", "</p>", "```"]
        self.assertEqual(md.strip_html_blocks(lines), lines)

    def test_fenced_h1_is_kept(self):
        lines = ["~~~html", "<h1>x</h1>", "~~~"]
        self.assertEqual(md.strip_html_blocks(lines), lines)

    def test_prose_chrome_is_still_stripped(self):
        self.assertEqual(md.strip_html_blocks(['<p align="center">', "x", "</p>", "<h1>y</h1>", "z"]), ["z"])

    def test_fenced_store_row_is_kept(self):
        lines = ["```", "**[Download](https://x)**", "[Modrinth](https://y)", "```"]
        self.assertEqual(md.strip_store_links(lines), lines)

    def test_fenced_html_survives_generation(self):
        text, problems = md.generate(readme("```html", '<p align="center">x</p>', "<h1>y</h1>", "```"))
        self.assertEqual(problems, [])
        self.assertIn('```html\n<p align="center">x</p>\n<h1>y</h1>\n```', text)


class Autolinks(unittest.TestCase):
    def test_autolink_with_query_is_not_a_tag(self):
        self.assertEqual(unsupported("See <https://example.com/p?x=1&y=2>."), [])
        self.assertEqual(md.check_no_relative_html_refs("See <https://example.com/p?x=1&y=2>."), [])

    def test_real_tag_is_still_read(self):
        self.assertEqual(len(md.check_no_relative_html_refs("<a href=docs/A.md>x</a>")), 1)


class BodyIntact(unittest.TestCase):
    def test_no_sections(self):
        self.assertEqual(len(md.check_body_is_intact("text\n", 0)), 1)

    def test_fewer_sections_than_readme(self):
        problems = md.check_body_is_intact("## One\n", 11)
        self.assertEqual(
            problems, ["generated description has 1 section headings; README.md has 11"]
        )

    def test_all_sections(self):
        self.assertEqual(md.check_body_is_intact("## One\n\n## Two\n", 2), [])

    def test_sections_counted_outside_fences_only(self):
        self.assertEqual(md.count_sections(["## A", "### B", "~~~", "## C", "~~~"]), 2)


class Generate(unittest.TestCase):
    def test_refusal_names_the_readme_line(self):
        with self.assertRaises(md.ReadmeError) as caught:
            md.generate(readme("[ref]: docs/A.md"))
        self.assertIn("README.md:3: reference definition", str(caught.exception))

    def test_fenced_example_survives_generation(self):
        text, problems = md.generate(
            readme("~~~", "[example](docs/A.md)", "~~~", "", "[real](docs/B.md)")
        )
        self.assertEqual(problems, [])
        self.assertIn("\n[example](docs/A.md)\n", text)
        self.assertIn("[real](" + BLOB + "docs/B.md)", text)


if __name__ == "__main__":
    unittest.main()
