package com.rag.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reranker for retrieval results.
 *
 * <p>Providers follow {@code docs/CONTRACT.md} section 9:
 * <ul>
 *   <li>{@code llm} — chat-model scoring on a 0-10 scale (one extra blocking call).</li>
 *   <li>{@code none} — keep the vector order, scores normalised to [0, 1].</li>
 *   <li>{@code local} — a sentence-transformers cross-encoder. Python only; here it
 *       warns once and falls back to {@code llm} rather than silently behaving
 *       differently from the other backend.</li>
 * </ul>
 *
 * <p>The score blend and the [0, 1] normalisation are shared with the Python
 * backend (contract section 7), so the confidence numbers mean the same thing on
 * either side. The old implementation blended {@code originalScore} unclamped,
 * and Milvus COSINE scores can be negative, which could push a result's score
 * below zero.
 */
@Service
@Slf4j
public class RerankerService {

    /** Characters of each candidate shown to the scoring model. */
    private static final int SNIPPET_CHARS = 500;

    /** Fewer than this and there is nothing worth reordering. */
    private static final int MIN_DOCS_TO_RERANK = 3;

    private static final String RERANK_PROMPT = """
            你是一个文档相关性评估专家。请评估以下每段文档与用户问题的相关程度。

            用户问题：%s

            请对每段文档评分（0-10分）：
            - 0-2分：完全无关
            - 3-4分：有微弱关联但不能直接回答问题
            - 5-6分：部分相关，包含一些有用信息
            - 7-8分：高度相关，包含回答所需的关键信息
            - 9-10分：完美匹配，直接回答了问题

            文档列表：
            %s

            请以JSON数组格式返回每个文档的评分：
            [{"index": 0, "score": 8, "reason": "简短原因"}]

            只返回JSON数组，不要其他内容。""";

    private final LlmService llmService;
    private final RagProperties props;
    private final Gson gson = new Gson();
    private volatile boolean localWarningLogged = false;

    public RerankerService(LlmService llmService, RagProperties props) {
        this.llmService = llmService;
        this.props = props;
    }

    /** A candidate passage: retrieval output, or reranker output. */
    public record Scored(String text, double score, Map<String, Object> metadata) {
    }

    public static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Blend a 0-10 rerank score with a normalised vector score. */
    private static double combine(double rerankScore, double vectorScore) {
        return 0.7 * (rerankScore / 10.0) + 0.3 * clamp01(vectorScore);
    }

    public List<Scored> rerank(String question, List<Scored> documents) {
        return rerank(question, documents, props.getRerankTopK(), props.getRerankThreshold());
    }

    public List<Scored> rerank(String question, List<Scored> documents, int topK, double threshold) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        String provider = props.getRerankerProvider() == null
                ? "llm"
                : props.getRerankerProvider().trim().toLowerCase();

        if ("none".equals(provider) || documents.size() < MIN_DOCS_TO_RERANK) {
            return passthrough(documents, topK);
        }
        if ("local".equals(provider) && !localWarningLogged) {
            localWarningLogged = true;
            log.warn("RERANKER_PROVIDER=local is not supported by the Spring backend; "
                    + "using LLM reranking instead. Set RERANKER_PROVIDER=llm to silence this.");
        }

        return rerankWithLlm(question, documents, topK, threshold);
    }

    /** Vector order, with scores normalised to the shared [0, 1] scale. */
    private static List<Scored> passthrough(List<Scored> documents, int topK) {
        List<Scored> results = new ArrayList<>(documents.size());
        for (Scored doc : documents) {
            results.add(new Scored(doc.text(), clamp01(doc.score()), doc.metadata()));
        }
        return sortAndTrim(results, topK);
    }

    private List<Scored> rerankWithLlm(String question, List<Scored> documents, int topK,
                                       double threshold) {
        try {
            StringBuilder docParts = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                Scored doc = documents.get(i);
                String text = doc.text() == null ? "" : doc.text();
                if (text.length() > SNIPPET_CHARS) {
                    text = text.substring(0, SNIPPET_CHARS);
                }
                Object source = doc.metadata() == null ? null : doc.metadata().get("source");
                docParts.append("[文档 ").append(i).append("] (来源: ")
                        .append(source == null ? "unknown" : source).append(")\n")
                        .append(text).append("\n\n");
            }

            String response = llmService.call(
                    String.format(RERANK_PROMPT, question, docParts.toString()));

            List<Map<String, Object>> scores = gson.fromJson(
                    GraphService.stripCodeFence(response),
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
            if (scores == null) {
                return passthrough(documents, topK);
            }

            List<Scored> results = new ArrayList<>();
            for (Map<String, Object> item : scores) {
                if (item == null) {
                    continue;
                }
                int index = intValue(item.get("index"), -1);
                Double rerankScore = doubleValue(item.get("score"));
                if (rerankScore == null || index < 0 || index >= documents.size()
                        || rerankScore < threshold) {
                    continue;
                }

                Scored doc = documents.get(index);
                Map<String, Object> metadata = new HashMap<>(
                        doc.metadata() == null ? Map.of() : doc.metadata());
                metadata.put("rerank_score", rerankScore);
                metadata.put("original_vector_score", doc.score());

                results.add(new Scored(doc.text(), combine(rerankScore, doc.score()), metadata));
            }
            return sortAndTrim(results, topK);

        } catch (Exception e) {
            log.warn("LLM reranking failed, returning vector order: {}", e.getMessage());
            return passthrough(documents, topK);
        }
    }

    private static List<Scored> sortAndTrim(List<Scored> results, int topK) {
        results.sort(Comparator.comparingDouble(Scored::score).reversed());
        int limit = Math.max(0, topK);
        return results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
