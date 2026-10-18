package com.rag.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recursive character text splitter, per file type.
 *
 * <p>Port of LangChain's {@code RecursiveCharacterTextSplitter}, which the Python
 * backend uses: try the coarsest separator that occurs in the text, recurse into
 * pieces that are still too big, then merge neighbouring pieces up to
 * {@code chunkSize} with {@code chunkOverlap} carried over. The previous
 * hand-rolled splitter here dropped the overlap on some paths and could emit
 * chunks larger than {@code chunkSize}, both of which hurt retrieval quality.
 *
 * <p>Chunk boundaries do <em>not</em> have to be byte-identical to the Python
 * backend's — {@code docs/CONTRACT.md} fixes the storage schema, not the
 * tokenisation — so the language separator lists below approximate LangChain's
 * {@code Language} presets rather than copying them exactly.
 */
public final class TextSplitter {

    private static final List<String> PLAIN =
            List.of("\n\n", "\n", ". ", " ", "");

    private static final List<String> MARKDOWN =
            List.of("\n# ", "\n## ", "\n### ", "\n#### ", "\n\n", "\n", " ", "");

    private static final List<String> TABULAR =
            List.of("\n\n", "\n", " ", "");

    private static final List<String> PYTHON =
            List.of("\nclass ", "\ndef ", "\n\tdef ", "\n    def ", "\n\n", "\n", " ", "");

    private static final List<String> JVM =
            List.of("\nclass ", "\ninterface ", "\nenum ", "\npublic ", "\nprotected ",
                    "\nprivate ", "\nstatic ", "\nif ", "\nfor ", "\nwhile ", "\nswitch ",
                    "\n\n", "\n", " ", "");

    private static final List<String> JS_TS =
            List.of("\nexport ", "\nfunction ", "\nclass ", "\ninterface ", "\ntype ",
                    "\nconst ", "\nlet ", "\nvar ", "\nif ", "\nfor ", "\nwhile ", "\nswitch ",
                    "\n\n", "\n", " ", "");

    private static final List<String> GO =
            List.of("\nfunc ", "\ntype ", "\nvar ", "\nconst ", "\nif ", "\nfor ", "\nswitch ",
                    "\n\n", "\n", " ", "");

    private static final List<String> RUST =
            List.of("\npub ", "\nfn ", "\nstruct ", "\nenum ", "\nimpl ", "\nmod ", "\ntrait ",
                    "\nif ", "\nfor ", "\nwhile ", "\nmatch ", "\n\n", "\n", " ", "");

    private static final List<String> C_LIKE =
            List.of("\nclass ", "\nstruct ", "\nvoid ", "\nint ", "\nstatic ", "\nif ", "\nfor ",
                    "\nwhile ", "\nswitch ", "\n\n", "\n", " ", "");

    private static final List<String> RUBY =
            List.of("\nclass ", "\nmodule ", "\ndef ", "\nif ", "\nunless ", "\nwhile ",
                    "\nbegin ", "\n\n", "\n", " ", "");

    private static final List<String> PHP =
            List.of("\nclass ", "\ninterface ", "\ntrait ", "\nfunction ", "\nif ", "\nforeach ",
                    "\nwhile ", "\nswitch ", "\n\n", "\n", " ", "");

    private static final Map<String, List<String>> SEPARATORS_BY_EXTENSION = Map.ofEntries(
            Map.entry(".py", PYTHON),
            Map.entry(".java", JVM),
            Map.entry(".kt", JVM),
            Map.entry(".scala", JVM),
            Map.entry(".js", JS_TS),
            Map.entry(".jsx", JS_TS),
            Map.entry(".ts", JS_TS),
            Map.entry(".tsx", JS_TS),
            Map.entry(".go", GO),
            Map.entry(".rs", RUST),
            Map.entry(".c", C_LIKE),
            Map.entry(".h", C_LIKE),
            Map.entry(".cpp", C_LIKE),
            Map.entry(".rb", RUBY),
            Map.entry(".php", PHP),
            Map.entry(".md", MARKDOWN),
            Map.entry(".csv", TABULAR),
            Map.entry(".xls", TABULAR),
            Map.entry(".xlsx", TABULAR));

    private TextSplitter() {
    }

    /** Split with the general-text separators. */
    public static List<String> split(String text, int chunkSize, int chunkOverlap) {
        return split(text, null, chunkSize, chunkOverlap);
    }

    /**
     * Split using separators appropriate for {@code fileType}.
     *
     * @param fileType file extension including the dot, e.g. {@code ".py"}; null or
     *                 unknown falls back to the general-text separators
     */
    public static List<String> split(String text, String fileType, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        int size = Math.max(1, chunkSize);
        // An overlap >= chunkSize makes the merge loop unable to make progress.
        int overlap = Math.max(0, Math.min(chunkOverlap, size - 1));
        return recurse(text, separatorsFor(fileType), size, overlap);
    }

    static List<String> separatorsFor(String fileType) {
        if (fileType == null || fileType.isEmpty()) {
            return PLAIN;
        }
        String ext = fileType.toLowerCase();
        if (!ext.startsWith(".")) {
            ext = "." + ext;
        }
        return SEPARATORS_BY_EXTENSION.getOrDefault(ext, PLAIN);
    }

    private static List<String> recurse(String text, List<String> separators,
                                        int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        // Coarsest separator that actually occurs; "" (character split) always does.
        String separator = separators.get(separators.size() - 1);
        List<String> remaining = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String candidate = separators.get(i);
            if (candidate.isEmpty()) {
                separator = candidate;
                remaining = List.of();
                break;
            }
            if (text.contains(candidate)) {
                separator = candidate;
                remaining = separators.subList(i + 1, separators.size());
                break;
            }
        }

        List<String> pieces = splitKeepingSeparator(text, separator);
        List<String> mergeable = new ArrayList<>();

        for (String piece : pieces) {
            if (piece.length() < chunkSize) {
                mergeable.add(piece);
                continue;
            }
            // Flush what fits before descending, so ordering is preserved.
            if (!mergeable.isEmpty()) {
                chunks.addAll(merge(mergeable, chunkSize, overlap));
                mergeable.clear();
            }
            if (remaining.isEmpty()) {
                // No finer separator left: emit oversized rather than losing text.
                chunks.add(piece);
            } else {
                chunks.addAll(recurse(piece, remaining, chunkSize, overlap));
            }
        }

        if (!mergeable.isEmpty()) {
            chunks.addAll(merge(mergeable, chunkSize, overlap));
        }
        return chunks;
    }

    /**
     * Split on {@code separator}, keeping it attached to the start of the following
     * piece so that headings and {@code def }/{@code class } markers stay with
     * their body.
     */
    private static List<String> splitKeepingSeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                parts.add(String.valueOf(text.charAt(i)));
            }
            return parts;
        }

        int first = text.indexOf(separator);
        if (first < 0) {
            parts.add(text);
            return parts;
        }
        if (first > 0) {
            parts.add(text.substring(0, first));
        }
        int start = first;
        while (true) {
            int next = text.indexOf(separator, start + separator.length());
            if (next < 0) {
                parts.add(text.substring(start));
                break;
            }
            parts.add(text.substring(start, next));
            start = next;
        }
        return parts;
    }

    /**
     * Greedily pack pieces into chunks of at most {@code chunkSize}, keeping
     * {@code overlap} characters of tail context at each boundary.
     */
    private static List<String> merge(List<String> pieces, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int total = 0;

        for (String piece : pieces) {
            int length = piece.length();
            if (total + length > chunkSize && !current.isEmpty()) {
                addIfNotBlank(chunks, String.join("", current));
                // Drop from the front until the carried-over tail is within the
                // overlap budget and the incoming piece fits.
                while (!current.isEmpty()
                        && (total > overlap || total + length > chunkSize)) {
                    total -= current.remove(0).length();
                }
            }
            current.add(piece);
            total += length;
        }

        addIfNotBlank(chunks, String.join("", current));
        return chunks;
    }

    private static void addIfNotBlank(List<String> chunks, String candidate) {
        String trimmed = candidate.strip();
        if (!trimmed.isEmpty()) {
            chunks.add(trimmed);
        }
    }
}
