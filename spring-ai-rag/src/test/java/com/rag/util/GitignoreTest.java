package com.rag.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirror of {@code langchain-rag/tests/test_gitignore.py}, case for case.
 *
 * <p>The pairing is the point: both backends import projects from disk, and if
 * they disagree about which files {@code .gitignore} excludes, the same upload
 * produces different knowledge bases depending on which one received it. A case
 * added on either side belongs on both.
 */
class GitignoreTest {

    private static boolean ignored(List<String> patterns, String path) {
        return Gitignore.isIgnored(path, Gitignore.parseLines(patterns), false);
    }

    private static boolean ignoredDir(List<String> patterns, String path) {
        return Gitignore.isIgnored(path, Gitignore.parseLines(patterns), true);
    }

    @Nested
    class Parsing {

        @Test
        void blankLinesAndCommentsAreDropped() {
            assertThat(Gitignore.parseLines(List.of("", "   ", "# a comment", "*.log")))
                    .containsExactly(new Gitignore.Rule("*.log", false, false, false));
        }

        @Test
        void trailingSlashMarksADirectoryPattern() {
            assertThat(Gitignore.parseLines(List.of("build/")))
                    .containsExactly(new Gitignore.Rule("build", false, true, false));
        }

        @Test
        void leadingBangMarksANegation() {
            assertThat(Gitignore.parseLines(List.of("!keep.log")))
                    .containsExactly(new Gitignore.Rule("keep.log", false, false, true));
        }

        @Test
        void innerSlashAnchorsToTheProjectRoot() {
            assertThat(Gitignore.parseLines(List.of("docs/*.md")))
                    .containsExactly(new Gitignore.Rule("docs/*.md", true, false, false));
        }

        @Test
        void aBareBangIsNotAPattern() {
            assertThat(Gitignore.parseLines(List.of("!", "!  "))).isEmpty();
        }
    }

    @Nested
    class Globs {

        @Test
        void extensionGlobMatchesAtAnyDepth() {
            assertThat(ignored(List.of("*.log"), "debug.log")).isTrue();
            assertThat(ignored(List.of("*.log"), "a/b/c/debug.log")).isTrue();
        }

        @Test
        void extensionGlobDoesNotMatchOtherExtensions() {
            assertThat(ignored(List.of("*.log"), "debug.txt")).isFalse();
        }

        @Test
        void noRulesIgnoresNothing() {
            assertThat(Gitignore.isIgnored("anything.log", List.of())).isFalse();
        }

        @Test
        void aQuestionMarkMatchesOneCharacter() {
            assertThat(ignored(List.of("log?.txt"), "log1.txt")).isTrue();
            assertThat(ignored(List.of("log?.txt"), "log12.txt")).isFalse();
        }

        @Test
        void aCharacterClassMatches() {
            assertThat(ignored(List.of("log[0-9].txt"), "log7.txt")).isTrue();
            assertThat(ignored(List.of("log[0-9].txt"), "logx.txt")).isFalse();
        }

        @Test
        void aNegatedCharacterClassMatches() {
            assertThat(ignored(List.of("log[!0-9].txt"), "logx.txt")).isTrue();
            assertThat(ignored(List.of("log[!0-9].txt"), "log7.txt")).isFalse();
        }

        @Test
        void aDotInThePatternIsLiteral() {
            // Regex-escaping matters: an unescaped `.` would make `a.log` match `axlog`.
            assertThat(ignored(List.of("a.log"), "axlog")).isFalse();
        }
    }

    @Nested
    @DisplayName("the over-matching half of the old bug")
    class SegmentBoundaries {

        @Test
        void aNamePatternMatchesAWholeSegmentOnly() {
            assertThat(ignored(List.of("src"), "src/app.py")).isTrue();
            assertThat(ignored(List.of("src"), "mysrc/app.py")).isFalse();
            assertThat(ignored(List.of("src"), "src_old/app.py")).isFalse();
        }

        @Test
        void aNamePatternMatchesANestedSegment() {
            assertThat(ignored(List.of("node_modules"), "frontend/node_modules/react/index.js"))
                    .isTrue();
        }
    }

    @Nested
    class DirectoryPatterns {

        @Test
        void directoryPatternMatchesTheDirectory() {
            assertThat(ignoredDir(List.of("build/"), "build")).isTrue();
        }

        @Test
        void directoryPatternExcludesTheContents() {
            assertThat(ignored(List.of("build/"), "build/main.js")).isTrue();
            assertThat(ignored(List.of("build/"), "a/build/main.js")).isTrue();
        }

        @Test
        void directoryPatternDoesNotMatchAFileOfThatName() {
            // `build/` in .gitignore leaves a file called `build` tracked.
            assertThat(ignored(List.of("build/"), "build")).isFalse();
        }
    }

    @Nested
    class Anchoring {

        @Test
        void anchoredPatternMatchesOnlyAtTheRoot() {
            assertThat(ignored(List.of("/dist"), "dist")).isTrue();
            assertThat(ignored(List.of("/dist"), "packages/dist")).isFalse();
        }

        @Test
        void anchoredPatternExcludesContents() {
            assertThat(ignored(List.of("/dist"), "dist/main.js")).isTrue();
        }

        @Test
        void multiSegmentPattern() {
            assertThat(ignored(List.of("docs/*.md"), "docs/readme.md")).isTrue();
            assertThat(ignored(List.of("docs/*.md"), "readme.md")).isFalse();
        }

        @Test
        void starDoesNotCrossADirectoryBoundary() {
            // Globbing the joined path matched this, because `*` becomes `.*` and
            // crosses `/`. git scopes `*` to one segment.
            assertThat(ignored(List.of("docs/*.md"), "docs/nested/readme.md")).isFalse();
        }

        @Test
        void doubleStarDoesCrossDirectoryBoundaries() {
            assertThat(ignored(List.of("docs/**/*.md"), "docs/nested/readme.md")).isTrue();
            assertThat(ignored(List.of("docs/**/*.md"), "docs/a/b/readme.md")).isTrue();
            // `**` also matches zero segments.
            assertThat(ignored(List.of("docs/**/*.md"), "docs/readme.md")).isTrue();
        }

        @Test
        void leadingDoubleStarMatchesAtAnyDepth() {
            assertThat(ignoredDir(List.of("**/generated"), "src/main/generated")).isTrue();
            assertThat(ignoredDir(List.of("**/generated"), "generated")).isTrue();
        }
    }

    @Nested
    class Negation {

        @Test
        void laterNegationReIncludes() {
            assertThat(ignored(List.of("*.log", "!keep.log"), "keep.log")).isFalse();
            assertThat(ignored(List.of("*.log", "!keep.log"), "debug.log")).isTrue();
        }

        @Test
        void orderMatters() {
            // Reversed, the negation is overridden again by the broader rule.
            assertThat(ignored(List.of("!keep.log", "*.log"), "keep.log")).isTrue();
        }
    }

    @Nested
    class Normalisation {

        @Test
        void windowsSeparatorsAreAccepted() {
            assertThat(ignored(List.of("*.log"), "a\\b\\debug.log")).isTrue();
        }

        @Test
        void leadingSlashOnThePathIsStripped() {
            assertThat(ignored(List.of("/dist"), "/dist/main.js")).isTrue();
        }

        @Test
        void aNullPathIsNotIgnored() {
            assertThat(Gitignore.isIgnored(null, Gitignore.parseLines(List.of("*.log")))).isFalse();
        }
    }

    @Nested
    @DisplayName("a .gitignore of the shape this repo's own has")
    class RealisticFile {

        private static final List<String> PATTERNS = List.of(
                "# secrets",
                ".env",
                "!.env.example",
                "",
                "target/",
                "__pycache__/",
                "node_modules/",
                ".next/",
                "*.db",
                "/data");

        @Test
        void envIsIgnoredButTheExampleIsNot() {
            assertThat(ignored(PATTERNS, ".env")).isTrue();
            assertThat(ignored(PATTERNS, ".env.example")).isFalse();
        }

        @Test
        void buildOutputIsIgnored() {
            assertThat(ignored(PATTERNS, "spring-ai-rag/target/classes/App.class")).isTrue();
            assertThat(ignored(PATTERNS, "frontend/.next/server/app.js")).isTrue();
            assertThat(ignored(PATTERNS, "langchain-rag/app/__pycache__/db.cpython-311.pyc"))
                    .isTrue();
        }

        @Test
        void sourceIsNotIgnored() {
            assertThat(ignored(PATTERNS, "langchain-rag/app/core/db.py")).isFalse();
            assertThat(ignored(PATTERNS, "frontend/src/lib/api.ts")).isFalse();
        }

        @Test
        void anchoredDataDirOnlyAtRoot() {
            assertThat(ignored(PATTERNS, "data/rag.db")).isTrue();
            assertThat(ignored(PATTERNS, "frontend/src/data/seed.ts")).isFalse();
        }
    }
}
