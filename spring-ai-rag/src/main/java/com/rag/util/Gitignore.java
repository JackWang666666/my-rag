package com.rag.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal {@code .gitignore} matching, mirroring
 * {@code langchain-rag/app/utils/gitignore.py}.
 *
 * <p>Both backends previously tested {@code pattern.contains(path)}-style
 * substring matches, which missed every glob ({@code *.log} matched nothing) and
 * over-matched badly (a pattern of {@code src} excluded {@code mysrc/app.py}).
 * This walks path segments and applies glob matching, covering the patterns that
 * actually appear in real files:
 *
 * <pre>
 *   build/          directory anywhere
 *   *.log           glob, any depth
 *   /dist           anchored to the project root
 *   docs/*.md       multi-segment ({@code *} does not cross {@code /}, as in git)
 *   **&#47;generated   {@code **} spans any number of segments
 *   !keep.log       negation
 * </pre>
 *
 * <p>Kept in step with the Python backend: the two must agree on which files a
 * project import skips, or the same upload produces different knowledge bases
 * depending on which backend received it. {@code GitignoreTest} mirrors
 * {@code tests/test_gitignore.py} case for case.
 */
public final class Gitignore {

    /** One parsed pattern. */
    public record Rule(String pattern, boolean anchored, boolean dirOnly, boolean negated) {}

    private Gitignore() {
    }

    /** Read {@code <projectRoot>/.gitignore}. Missing or unreadable file gives no rules. */
    public static List<Rule> parse(Path projectRoot) {
        Path gitignore = projectRoot.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return List.of();
        }
        try {
            return parseLines(Files.readAllLines(gitignore, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    public static List<Rule> parseLines(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            boolean negated = line.startsWith("!");
            if (negated) {
                line = line.substring(1).strip();
                if (line.isEmpty()) {
                    continue;
                }
            }

            boolean dirOnly = line.endsWith("/");
            if (dirOnly) {
                line = line.substring(0, line.length() - 1);
            }

            // A pattern containing a non-trailing slash is anchored to the root.
            boolean anchored = line.contains("/");
            line = strip(line, '/');
            if (line.isEmpty()) {
                continue;
            }

            rules.add(new Rule(line, anchored, dirOnly, negated));
        }
        return rules;
    }

    /**
     * Whether {@code relPath} (relative, forward slashes) is ignored.
     *
     * <p>Later rules win, so a trailing {@code !keep.log} can re-include a file.
     */
    public static boolean isIgnored(String relPath, List<Rule> rules, boolean isDir) {
        if (rules.isEmpty() || relPath == null) {
            return false;
        }
        String path = strip(relPath.replace('\\', '/'), '/');
        boolean ignored = false;
        for (Rule rule : rules) {
            if (matches(rule, path, isDir)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    public static boolean isIgnored(String relPath, List<Rule> rules) {
        return isIgnored(relPath, rules, false);
    }

    private static boolean matches(Rule rule, String relPath, boolean isDir) {
        String[] segments = relPath.split("/");

        // A directory pattern (`build/`) matches the path itself only when the path
        // really is a directory, but it always excludes everything underneath. So try
        // the full path first, then every ancestor prefix — those are directories by
        // construction, which is what makes `build/` exclude `build/main.js`.
        if ((isDir || !rule.dirOnly())
                && matchPrefix(rule.pattern(), rule.anchored(), segments, segments.length)) {
            return true;
        }
        for (int depth = 1; depth < segments.length; depth++) {
            if (matchPrefix(rule.pattern(), rule.anchored(), segments, depth)) {
                return true;
            }
        }
        return false;
    }

    /** Match {@code pattern} against the first {@code depth} segments of a path. */
    private static boolean matchPrefix(String pattern, boolean anchored, String[] segments, int depth) {
        String[] patternSegments = pattern.split("/");
        if (anchored) {
            return matchSegments(patternSegments, 0, segments, 0, depth);
        }
        // Unanchored patterns may start at any depth but must end at `depth`.
        for (int start = 0; start < depth; start++) {
            if (matchSegments(patternSegments, 0, segments, start, depth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match {@code p[pi..]} against {@code s[si..end)} one segment at a time, with
     * {@code **} spanning any number of segments.
     *
     * <p>Segment-wise is the point: matching the glob against the joined path let
     * {@code *} cross a directory boundary, so {@code docs/*.md} matched
     * {@code docs/nested/readme.md}, which git does not.
     */
    private static boolean matchSegments(String[] p, int pi, String[] s, int si, int end) {
        if (pi == p.length) {
            return si == end;
        }
        if ("**".equals(p[pi])) {
            for (int i = si; i <= end; i++) {
                if (matchSegments(p, pi + 1, s, i, end)) {
                    return true;
                }
            }
            return false;
        }
        if (si == end || !glob(p[pi], s[si])) {
            return false;
        }
        return matchSegments(p, pi + 1, s, si + 1, end);
    }

    /**
     * Shell-style glob match for a single path segment.
     *
     * <p>{@code java.nio.file.FileSystems.getDefault().getPathMatcher("glob:")} is
     * not usable here: it needs a {@code Path}, and building one per segment per
     * rule is far more allocation than a cached-free regex over a short string.
     */
    static boolean glob(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int close = pattern.indexOf(']', i + 1);
                    if (close < 0) {
                        regex.append("\\[");
                    } else {
                        String body = pattern.substring(i + 1, close);
                        regex.append('[').append(body.startsWith("!") ? "^" + body.substring(1) : body)
                                .append(']');
                        i = close;
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        try {
            return value.matches(regex.toString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String strip(String value, char ch) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ch) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ch) {
            end--;
        }
        return value.substring(start, end);
    }
}
