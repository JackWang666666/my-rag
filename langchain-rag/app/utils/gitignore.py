"""Minimal .gitignore pattern matching.

The previous implementation tested `pattern in relative_path`, which both missed
every glob (`*.log` matched nothing) and over-matched badly (a pattern of `src`
excluded `mysrc/app.py`). This walks the path segments and applies fnmatch, which
covers the patterns that actually show up in real .gitignore files:

    build/          directory anywhere
    *.log           glob, any depth
    /dist           anchored to the project root
    docs/*.md       multi-segment (`*` does not cross `/`, as in git)
    **/generated    `**` spans any number of segments
    !keep.log       negation

Kept in step with the Spring backend's com.rag.util.Gitignore -- the two must agree
on which files a project import skips, or the same upload produces different
knowledge bases depending on the backend.
"""

import fnmatch
import os
from typing import List, Tuple

# (pattern, anchored, dir_only, negated)
Rule = Tuple[str, bool, bool, bool]


def parse_gitignore(project_root: str) -> List[Rule]:
    """Read `<project_root>/.gitignore` into rules. Missing file -> []."""
    gitignore_path = os.path.join(project_root, ".gitignore")
    if not os.path.exists(gitignore_path):
        return []
    with open(gitignore_path, "r", encoding="utf-8", errors="ignore") as f:
        return parse_patterns(f.read().splitlines())


def parse_patterns(lines: List[str]) -> List[Rule]:
    """Turn raw .gitignore lines into rules."""
    rules: List[Rule] = []
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        negated = line.startswith("!")
        if negated:
            line = line[1:].strip()
            if not line:
                continue

        dir_only = line.endswith("/")
        if dir_only:
            line = line[:-1]

        # A pattern containing a non-trailing slash is anchored to the root.
        anchored = "/" in line
        line = line.strip("/")
        if not line:
            continue

        rules.append((line, anchored, dir_only, negated))
    return rules


def _match_segments(pattern_segments: List[str], path_segments: List[str]) -> bool:
    """Match segment lists, with `**` spanning any number of segments."""
    if not pattern_segments:
        return not path_segments
    head = pattern_segments[0]
    if head == "**":
        rest = pattern_segments[1:]
        for i in range(len(path_segments) + 1):
            if _match_segments(rest, path_segments[i:]):
                return True
        return False
    if not path_segments:
        return False
    # fnmatchcase, not fnmatch: fnmatch applies os.path.normcase, which lowercases
    # on Windows. git is case-sensitive by default, and so is Milvus' metadata.
    if not fnmatch.fnmatchcase(path_segments[0], head):
        return False
    return _match_segments(pattern_segments[1:], path_segments[1:])


def _match_path(pattern: str, path_segments: List[str]) -> bool:
    """Whether `pattern` matches exactly this segment list.

    Matching runs per segment, so `*` cannot cross a directory boundary — a plain
    `fnmatch` on the joined path made `docs/*.md` match `docs/nested/readme.md`,
    which git does not.
    """
    return _match_segments(pattern.split("/"), path_segments)


def _match_prefix(pattern: str, anchored: bool, segments: List[str], depth: int) -> bool:
    """Match `pattern` against the first `depth` segments of a path."""
    target = segments[:depth]
    if anchored:
        return _match_path(pattern, target)
    # Unanchored patterns may start at any depth but must end at `depth`.
    return any(_match_path(pattern, target[i:]) for i in range(depth))


def _matches(rule: Rule, rel_path: str, is_dir: bool) -> bool:
    pattern, anchored, dir_only, _negated = rule
    segments = rel_path.split("/")

    # A directory pattern (`build/`) matches the path itself only when the path
    # really is a directory, but it always excludes everything underneath. So try
    # the full path first, then every ancestor prefix — those are directories by
    # construction, which is what makes `build/` exclude `build/main.js`.
    if (is_dir or not dir_only) and _match_prefix(pattern, anchored, segments, len(segments)):
        return True
    for depth in range(1, len(segments)):
        if _match_prefix(pattern, anchored, segments, depth):
            return True
    return False


def is_ignored(rel_path: str, rules: List[Rule], is_dir: bool = False) -> bool:
    """Whether `rel_path` (relative, posix separators) is ignored.

    Later rules win, so a trailing `!keep.log` can re-include a file.
    """
    if not rules:
        return False

    rel_path = rel_path.replace("\\", "/").strip("/")
    ignored = False
    for rule in rules:
        if _matches(rule, rel_path, is_dir):
            ignored = not rule[3]  # negated -> re-include
    return ignored
