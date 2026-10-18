"""Tests for app.utils.gitignore.

The predecessor of this module tested `pattern in relative_path`, so `*.log`
matched nothing at all and a pattern of `src` excluded `mysrc/app.py`. Each test
below pins one of the behaviours that fixed.
"""

from app.utils.gitignore import is_ignored, parse_patterns


def ignored(patterns, path, is_dir=False):
    return is_ignored(path, parse_patterns(patterns), is_dir=is_dir)


class TestParsing:
    def test_blank_lines_and_comments_are_dropped(self):
        assert parse_patterns(["", "   ", "# a comment", "*.log"]) == [
            ("*.log", False, False, False)
        ]

    def test_trailing_slash_marks_a_directory_pattern(self):
        assert parse_patterns(["build/"]) == [("build", False, True, False)]

    def test_leading_bang_marks_a_negation(self):
        assert parse_patterns(["!keep.log"]) == [("keep.log", False, False, True)]

    def test_inner_slash_anchors_to_the_project_root(self):
        # "docs/*.md" has a non-trailing slash, so git anchors it.
        assert parse_patterns(["docs/*.md"]) == [("docs/*.md", True, False, False)]

    def test_a_bare_bang_is_not_a_pattern(self):
        assert parse_patterns(["!", "!  "]) == []


class TestGlobs:
    def test_extension_glob_matches_at_any_depth(self):
        assert ignored(["*.log"], "debug.log")
        assert ignored(["*.log"], "a/b/c/debug.log")

    def test_extension_glob_does_not_match_other_extensions(self):
        assert not ignored(["*.log"], "debug.txt")

    def test_no_rules_ignores_nothing(self):
        assert not is_ignored("anything.log", [])


class TestSegmentBoundaries:
    """The over-matching half of the old bug."""

    def test_a_name_pattern_matches_a_whole_segment_only(self):
        assert ignored(["src"], "src/app.py")
        assert not ignored(["src"], "mysrc/app.py")
        assert not ignored(["src"], "src_old/app.py")

    def test_a_name_pattern_matches_a_nested_segment(self):
        assert ignored(["node_modules"], "frontend/node_modules/react/index.js")


class TestDirectoryPatterns:
    def test_directory_pattern_matches_the_directory(self):
        assert ignored(["build/"], "build", is_dir=True)

    def test_directory_pattern_excludes_the_contents(self):
        assert ignored(["build/"], "build/main.js")
        assert ignored(["build/"], "a/build/main.js")

    def test_directory_pattern_does_not_match_a_file_of_that_name(self):
        # `build/` in .gitignore leaves a file called `build` tracked.
        assert not ignored(["build/"], "build", is_dir=False)


class TestAnchoring:
    def test_anchored_pattern_matches_only_at_the_root(self):
        assert ignored(["/dist"], "dist")
        assert not ignored(["/dist"], "packages/dist")

    def test_anchored_pattern_excludes_contents(self):
        assert ignored(["/dist"], "dist/main.js")

    def test_multi_segment_pattern(self):
        assert ignored(["docs/*.md"], "docs/readme.md")
        assert not ignored(["docs/*.md"], "readme.md")

    def test_star_does_not_cross_a_directory_boundary(self):
        # A plain fnmatch on the joined path matched this, because fnmatch turns
        # `*` into `.*`. git scopes `*` to one segment.
        assert not ignored(["docs/*.md"], "docs/nested/readme.md")

    def test_double_star_does_cross_directory_boundaries(self):
        assert ignored(["docs/**/*.md"], "docs/nested/readme.md")
        assert ignored(["docs/**/*.md"], "docs/a/b/readme.md")
        # `**` also matches zero segments.
        assert ignored(["docs/**/*.md"], "docs/readme.md")

    def test_leading_double_star_matches_at_any_depth(self):
        assert ignored(["**/generated"], "src/main/generated", is_dir=True)
        assert ignored(["**/generated"], "generated", is_dir=True)


class TestNegation:
    def test_later_negation_re_includes(self):
        assert not ignored(["*.log", "!keep.log"], "keep.log")
        assert ignored(["*.log", "!keep.log"], "debug.log")

    def test_order_matters(self):
        # Reversed, the negation is overridden again by the broader rule.
        assert ignored(["!keep.log", "*.log"], "keep.log")


class TestNormalisation:
    def test_windows_separators_are_accepted(self):
        assert ignored(["*.log"], "a\\b\\debug.log")

    def test_leading_slash_on_the_path_is_stripped(self):
        assert ignored(["/dist"], "/dist/main.js")


class TestRealisticFile:
    """A .gitignore of the shape this repo's own has."""

    PATTERNS = [
        "# secrets",
        ".env",
        "!.env.example",
        "",
        "target/",
        "__pycache__/",
        "node_modules/",
        ".next/",
        "*.db",
        "/data",
    ]

    def test_env_is_ignored_but_the_example_is_not(self):
        assert ignored(self.PATTERNS, ".env")
        assert not ignored(self.PATTERNS, ".env.example")

    def test_build_output_is_ignored(self):
        assert ignored(self.PATTERNS, "spring-ai-rag/target/classes/App.class")
        assert ignored(self.PATTERNS, "frontend/.next/server/app.js")
        assert ignored(self.PATTERNS, "langchain-rag/app/__pycache__/db.cpython-311.pyc")

    def test_source_is_not_ignored(self):
        assert not ignored(self.PATTERNS, "langchain-rag/app/core/db.py")
        assert not ignored(self.PATTERNS, "frontend/src/lib/api.ts")

    def test_anchored_data_dir_only_at_root(self):
        assert ignored(self.PATTERNS, "data/rag.db")
        assert not ignored(self.PATTERNS, "frontend/src/data/seed.ts")
