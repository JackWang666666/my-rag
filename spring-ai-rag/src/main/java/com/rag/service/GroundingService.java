package com.rag.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Faithfulness check: does the generated answer actually follow from the
 * retrieved context, or did the model wander off and invent things?
 *
 * <p>Not part of {@code docs/CONTRACT.md} — this is Java-only. The Python
 * backend has no equivalent step, so the two backends intentionally diverge
 * here: Java spends one extra blocking LLM call verifying the answer (and,
 * on failure, a second call regenerating it) before returning.
 */
@Service
@Slf4j
public class GroundingService {

    private static final int CONTEXT_CHARS = 4000;
    private static final int ANSWER_CHARS = 2000;

    private static final String GROUNDING_PROMPT = """
            你是一个忠实性校验器。请判断下面的回答是否完全基于给定的参考资料作答，
            没有编造参考资料之外的事实。

            参考资料：
            %s

            用户问题：
            %s

            助手回答：
            %s

            判断标准：
            - 回答中的关键信息都能在参考资料中找到依据 → grounded = true
            - 回答包含参考资料未提及的事实、编造的细节，或答非所问 → grounded = false
            - 如果参考资料本就不包含答案，回答诚实地说"资料中没有相关信息" → grounded = true

            请以JSON格式返回：
            {"grounded": true/false, "reason": "简短理由"}

            只返回JSON，不要其他内容。""";

    private final LlmService llmService;
    private final Gson gson = new Gson();

    public GroundingService(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * Fails open (returns {@code true}): an unreliable verifier should not turn
     * into a retry on every single request, matching how
     * {@link QueryAnalyzerService} and {@link RerankerService} degrade to a safe
     * default rather than propagating.
     */
    public boolean isGrounded(String question, String context, String answer) {
        try {
            String prompt = String.format(GROUNDING_PROMPT,
                    truncate(context, CONTEXT_CHARS), question, truncate(answer, ANSWER_CHARS));
            String response = llmService.call(prompt);
            Map<String, Object> raw = gson.fromJson(GraphService.stripCodeFence(response),
                    new TypeToken<Map<String, Object>>() {}.getType());
            if (raw == null) {
                return true;
            }
            Object grounded = raw.get("grounded");
            if (grounded instanceof Boolean b) {
                return b;
            }
            if (grounded instanceof String s) {
                return !"false".equalsIgnoreCase(s.trim());
            }
            return true;
        } catch (Exception e) {
            log.warn("Grounding check failed, assuming grounded: {}", e.getMessage());
            return true;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
