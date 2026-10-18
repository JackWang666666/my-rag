package com.rag.service;

import com.rag.config.ChatModelRegistry;
import com.rag.config.SettingsStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Chat model access, with the provider selected at runtime from settings.
 *
 * <p>{@code ChatModel} already extends {@code StreamingChatModel} in Spring AI
 * 1.0.0-M1, so {@code stream()} is always available. The old
 * {@code instanceof StreamingChatModel} narrowing was not just redundant, it was
 * a compile error ("expression type cannot be a subtype of the pattern type").
 *
 * <p>The provider lookup goes through {@link ChatModelRegistry} because injecting
 * a {@code Map<String, ChatModel>} gets bean names, not provider names — see that
 * class for why the runtime switch used to be a silent no-op.
 *
 * <p>{@link #mcpTools} is attached to every {@link #call}/{@link #callLogged}/
 * {@link #stream} prompt via {@code ToolCallingChatOptions}. Both {@code
 * ChatModel.call} and {@code ChatModel.stream} run the tool-call loop
 * internally (default {@code internalToolExecutionEnabled = true}), so no
 * explicit loop is needed here — see docs/CONTRACT.md section 10. Verified by
 * reading the 1.0.0 sources of {@code OpenAiChatModel}/{@code OllamaChatModel}:
 * both their {@code stream()} implementations check {@code
 * toolExecutionEligibilityPredicate.isToolExecutionRequired}, call {@code
 * toolCallingManager.executeToolCalls}, and recurse into {@code internalStream}
 * with the tool results appended — the same loop {@code call()} runs, just
 * inside the reactive pipeline. An empty {@code mcpTools} array (no MCP servers
 * configured) is a no-op, identical to the pre-MCP behavior.
 */
@Service
@Slf4j
public class LlmService {

    private final ChatModelRegistry registry;
    private final SettingsStore settings;
    private final ToolCallback[] mcpTools;

    public LlmService(ChatModelRegistry registry, SettingsStore settings, ToolCallback[] mcpToolCallbacks) {
        this.registry = registry;
        this.settings = settings;
        this.mcpTools = mcpToolCallbacks;
    }

    public ChatModel getChatModel() {
        return registry.resolve(settings.current().getLlmProvider());
    }

    private ChatOptions toolOptions() {
        return ToolCallingChatOptions.builder().toolCallbacks(mcpTools).build();
    }

    /** Stream a single-prompt completion as text deltas. */
    public Flux<String> stream(String prompt) {
        return stream(new Prompt(prompt, toolOptions()));
    }

    /** Stream a multi-message completion (system + history + question). */
    public Flux<String> stream(List<Message> messages) {
        return stream(new Prompt(messages, toolOptions()));
    }

    private Flux<String> stream(Prompt prompt) {
        return getChatModel().stream(prompt)
                .map(LlmService::contentOf)
                .filter(content -> !content.isEmpty());
    }

    /** Blocking completion. Used by the analyzer, reranker and graph extractor. */
    public String call(String prompt) {
        try {
            ChatResponse response = getChatModel().call(new Prompt(prompt, toolOptions()));
            return contentOf(response);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Blocking completion over a full message list. Used for the first answer and
     * the bounded regenerate-once retry, where the caller needs the complete text
     * before deciding whether to keep it.
     */
    public String call(List<Message> messages) {
        try {
            ChatResponse response = getChatModel().call(new Prompt(messages, toolOptions()));
            return contentOf(response);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private static String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * One row of the "thinking process" shown to the user — docs/CONTRACT.md
     * section 4, event {@code llmCall}. Token fields are {@code null} (not
     * {@code 0}) when usage could not be determined, e.g. a streamed answer on
     * the {@code direct} route: this Spring AI version's OpenAI client has no
     * {@code stream_options} support, so streamed usage is never available.
     */
    public record LlmCallRecord(String step, String model, Long promptTokens,
                                Long completionTokens, Long totalTokens, long durationMs,
                                String contentPreview) {}

    /** Result of a logged, non-streaming call: the text plus its call record. */
    public record Call(String content, LlmCallRecord record) {}

    /**
     * Blocking completion that also captures token usage, unlike {@link #call}.
     * Used by callers that feed the "thinking process" (analyzer, grounding,
     * main answer generation on rag/graph/hybrid routes).
     */
    public Call callLogged(String step, String prompt) {
        return callLogged(step, new Prompt(prompt, toolOptions()));
    }

    public Call callLogged(String step, List<Message> messages) {
        return callLogged(step, new Prompt(messages, toolOptions()));
    }

    private Call callLogged(String step, Prompt prompt) {
        long start = System.nanoTime();
        ChatModel model = getChatModel();
        try {
            ChatResponse response = model.call(prompt);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return new Call(contentOf(response),
                    new LlmCallRecord(step, modelNameOf(model), usagePromptTokens(response),
                            usageCompletionTokens(response), usageTotalTokens(response),
                            durationMs, null));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.error("LLM call failed", e);
            throw new LoggedCallException(e,
                    new LlmCallRecord(step, modelNameOf(model), null, null, null, durationMs,
                            "调用失败: " + e.getMessage()));
        }
    }

    /** Carries the partial {@link LlmCallRecord} for a failed {@link #callLogged}. */
    public static class LoggedCallException extends RuntimeException {
        private final LlmCallRecord record;

        public LoggedCallException(Throwable cause, LlmCallRecord record) {
            super("LLM call failed: " + cause.getMessage(), cause);
            this.record = record;
        }

        public LlmCallRecord record() {
            return record;
        }
    }

    private static String modelNameOf(ChatModel model) {
        return model == null ? null : model.getClass().getSimpleName();
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private static Long usagePromptTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null || usage.getPromptTokens() == null
                ? null : usage.getPromptTokens().longValue();
    }

    private static Long usageCompletionTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null || usage.getCompletionTokens() == null
                ? null : usage.getCompletionTokens().longValue();
    }

    private static Long usageTotalTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null || usage.getTotalTokens() == null
                ? null : usage.getTotalTokens().longValue();
    }
}
