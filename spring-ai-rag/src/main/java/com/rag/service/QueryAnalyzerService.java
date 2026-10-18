package com.rag.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rag.store.MetadataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-pass query analysis: routing + coreference resolution + rewriting.
 *
 * <p>Replaces the old {@code RouterService} + {@code QueryRewriteService} pair.
 * Both ran as separate blocking LLM calls before retrieval could start, so every
 * question paid two full round-trips before the first token. They need the same
 * inputs and produce complementary outputs, so they are one call now.
 *
 * <p>The prompt and the output shape are shared with the Python backend — see
 * {@code docs/CONTRACT.md} section 8.
 */
@Service
@Slf4j
public class QueryAnalyzerService {

    private static final Set<String> VALID_ROUTES = Set.of("rag", "graph", "hybrid", "direct");
    private static final Set<String> VALID_INTENTS =
            Set.of("factual", "analytical", "comparative", "procedural");
    private static final Set<String> VALID_COMPLEXITY = Set.of("simple", "moderate", "complex");

    /** Messages of history fed to the analyzer. */
    private static final int HISTORY_WINDOW = 6;

    private static final String ANALYZE_PROMPT = """
            你是一个查询分析器。请一次性完成路由决策与查询优化。

            当前知识库类型：%s
            当前知识库名称：%s

            全局知识库（内容共享，检索时会和当前知识库一并被搜索）：
            %s

            对话历史：
            %s

            用户问题：%s

            请完成以下工作：

            1. **路由决策**：判断最佳处理策略
               - rag：需要检索知识库文档（事实性问题、需要引用原文）
               - graph：需要查询知识图谱（关系类问题，如"A和B什么关系"、"谁创建了X"）
               - hybrid：同时需要文档检索和图谱查询（复杂问题）
               - direct：可直接回答，无需检索（闲聊、通用知识，且与当前知识库和上面
                 列出的全局知识库**都**无关）

               注意：只要问题落在**任何一个**知识库的主题范围内（当前的或全局的），
               就选 rag。不要因为"与当前知识库无关"而选 direct —— 全局知识库同样会
               被检索，主题不在当前库里并不代表检索不到。
            2. **意图识别**：factual / analytical / comparative / procedural
            3. **指代消解**：结合历史，把"它"、"这个"、"上面提到的"替换为具体实体
            4. **查询改写**：生成 2-3 个语义等价但表述不同的查询，用于多路召回
            5. **关键词与图谱实体**：提取用于精确匹配的关键词，以及需要在图谱中查询的实体

            请以JSON格式返回：
            {
                "route": "rag/graph/hybrid/direct",
                "confidence": 0.0-1.0,
                "reason": "简短理由",
                "intent": "factual/analytical/comparative/procedural",
                "resolvedQuery": "消解指代后的完整问题",
                "rewrittenQueries": ["改写查询1", "改写查询2"],
                "keywords": ["关键词1", "关键词2"],
                "graphEntities": ["实体1", "实体2"],
                "complexity": "simple/moderate/complex"
            }

            只返回JSON，不要其他内容。""";

    private final LlmService llmService;
    private final Gson gson = new Gson();

    public QueryAnalyzerService(LlmService llmService) {
        this.llmService = llmService;
    }

    /** Analyzer output. All fields are non-null; the lists may be empty. */
    public record Analysis(
            String route,
            double confidence,
            String reason,
            String intent,
            String resolvedQuery,
            List<String> rewrittenQueries,
            List<String> keywords,
            List<String> graphEntities,
            String complexity) {

        /** Terms to look up in the graph, falling back to plain keywords. */
        public List<String> graphTerms() {
            return graphEntities.isEmpty() ? keywords : graphEntities;
        }
    }

    /** Safe default: plain RAG with the question unchanged. */
    static Analysis fallback(String question, String reason) {
        return new Analysis("rag", 0.5, reason, "factual", question,
                List.of(), List.of(), List.of(), "simple");
    }

    /**
     * @param globalKbs the global KBs that retrieval will actually search — pass the
     *                  same, already-capped list, not everything in the database.
     *                  Routing {@code rag} for a KB that is then not searched is no
     *                  better than routing {@code direct}.
     */
    public Analysis analyze(String question, String kbType, String kbName,
                            List<MetadataStore.MessageRow> history,
                            List<MetadataStore.KbRow> globalKbs) {
        Map<String, Object> raw;
        try {
            String prompt = String.format(ANALYZE_PROMPT,
                    kbType == null || kbType.isBlank() ? "document" : kbType,
                    kbName == null || kbName.isBlank() ? "未命名" : kbName,
                    formatGlobalKbs(globalKbs),
                    formatHistory(history),
                    question);

            String response = llmService.call(prompt);
            raw = gson.fromJson(GraphService.stripCodeFence(response),
                    new TypeToken<Map<String, Object>>() {}.getType());
        } catch (Exception e) {
            log.warn("Query analysis failed, defaulting to RAG: {}", e.getMessage());
            return fallback(question, "查询分析失败，使用默认RAG");
        }
        if (raw == null) {
            return fallback(question, "查询分析返回格式异常，使用默认RAG");
        }

        String route = oneOf(pick(raw, "route"), VALID_ROUTES, "rag");
        String intent = oneOf(pick(raw, "intent"), VALID_INTENTS, "factual");
        String complexity = oneOf(pick(raw, "complexity"), VALID_COMPLEXITY, "simple");

        double confidence = clamp01(asDouble(pick(raw, "confidence"), 0.8));

        Object resolved = pick(raw, "resolvedQuery", "resolved_query");
        String resolvedQuery = resolved instanceof String s && !s.isBlank() ? s.trim() : question;

        Object reason = pick(raw, "reason");

        return new Analysis(
                route,
                confidence,
                reason instanceof String s ? s : "",
                intent,
                resolvedQuery,
                asStringList(pick(raw, "rewrittenQueries", "rewritten_queries"), 3),
                asStringList(pick(raw, "keywords"), 8),
                asStringList(pick(raw, "graphEntities", "graph_entities"), 8),
                complexity);
    }

    /**
     * The global KBs as the router sees them.
     *
     * <p>Descriptions are included because a name on its own often does not say
     * whether a question is covered — "天龙八部" does, "公司文档" does not.
     */
    private static String formatGlobalKbs(List<MetadataStore.KbRow> globalKbs) {
        if (globalKbs == null || globalKbs.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        for (MetadataStore.KbRow kb : globalKbs) {
            String name = kb.name() == null || kb.name().isBlank() ? "未命名" : kb.name().trim();
            String description = kb.description() == null ? "" : kb.description().trim();
            parts.add(description.isEmpty() ? "- " + name : "- " + name + "：" + description);
        }
        return String.join("\n", parts);
    }

    private static String formatHistory(List<MetadataStore.MessageRow> history) {
        if (history == null || history.isEmpty()) {
            return "无";
        }
        List<MetadataStore.MessageRow> window = history.size() > HISTORY_WINDOW
                ? history.subList(history.size() - HISTORY_WINDOW, history.size())
                : history;

        List<String> parts = new ArrayList<>();
        for (MetadataStore.MessageRow msg : window) {
            String content = msg.content() == null ? "" : msg.content();
            if (content.length() > 200) {
                content = content.substring(0, 200);
            }
            parts.add(("user".equals(msg.role()) ? "用户" : "助手") + ": " + content);
        }
        return parts.isEmpty() ? "无" : String.join("\n", parts);
    }

    /** The model may answer in camelCase (as asked) or snake_case. */
    private static Object pick(Map<String, Object> raw, String... names) {
        for (String name : names) {
            Object value = raw.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String oneOf(Object value, Set<String> allowed, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim().toLowerCase();
        return allowed.contains(text) ? text : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static List<String> asStringList(Object value, int limit) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** Only used by callers that want HyDE; kept close to the analyzer prompt. */
    public String generateHydeDocument(String question) {
        try {
            String prompt = "请根据以下问题，写一段假设性的答案文本（约100-200字），这段文本应该包含回答该问题"
                    + "所需的关键信息和术语。不需要准确，但要包含相关领域的专业词汇和概念。\n\n"
                    + "问题：" + question + "\n\n假设性答案：";
            String text = llmService.call(prompt);
            return text == null || text.isBlank() ? question : text.trim();
        } catch (Exception e) {
            log.warn("HyDE generation failed: {}", e.getMessage());
            return question;
        }
    }
}
