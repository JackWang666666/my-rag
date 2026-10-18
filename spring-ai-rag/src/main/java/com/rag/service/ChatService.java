package com.rag.service;

import com.rag.config.RagProperties;
import com.rag.model.ChatMessage;
import com.rag.model.dto.SessionResponse;
import com.rag.store.MetadataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG chat pipeline.
 *
 * <p>Sessions and messages live in the shared SQLite store, not in a
 * {@code ConcurrentHashMap}: history now survives a restart, is visible to the
 * Python backend, and — because every session row carries an {@code owner} — can
 * actually be access-checked. The in-memory map it replaces also leaked memory
 * for the lifetime of the process.
 *
 * <p>Mirrors {@code langchain-rag/app/services/chat.py}; the SSE event names and
 * the confidence formula are fixed by {@code docs/CONTRACT.md} sections 4 and 7.
 *
 * <p>One deliberate divergence: after generating an answer for the rag/graph/
 * hybrid routes, this backend runs it through {@link GroundingService} and
 * regenerates once if it fails. The Python backend has no equivalent, and this
 * is not part of the contract — the two backends are allowed to disagree here.
 * It also means the {@code chunk} event for those routes now carries the whole
 * answer in one frame instead of multiple streamed pieces, since verifying
 * requires the complete text first.
 */
@Service
@Slf4j
public class ChatService {

    /** How many past messages to feed back into the model and the analyzer. */
    private static final int HISTORY_WINDOW = 6;

    /** Candidates retrieved for the resolved query, each rewrite, each global KB. */
    private static final int TOP_K_PRIMARY = 10;
    private static final int TOP_K_REWRITE = 5;
    private static final int TOP_K_GLOBAL = 3;

    /** Rewritten queries actually issued, and global KBs actually searched. */
    private static final int MAX_REWRITES = 2;
    private static final int MAX_GLOBAL_KBS = 3;

    /** Characters of each citation returned to the frontend. */
    private static final int SOURCE_SNIPPET_CHARS = 200;

    /**
     * {@code knowledgeBaseId} value meaning "search every private KB I own",
     * instead of a real KB id. Safe to overload: real ids are 32-char uuid4 hex
     * and never look like this. See docs/CONTRACT.md section 5.
     */
    public static final String ALL_KB_SENTINEL = "__all__";

    /** Caps on "all my knowledge bases" mode. */
    private static final int MAX_ALL_MODE_KBS = 5;
    private static final int TOP_K_ALL_MODE_PER_KB = 5;


    private static final String RAG_SYSTEM_PROMPT_DOCUMENT = """
            You are a helpful assistant. Answer questions based on the provided context documents.
            If the context does not contain enough information to answer the question, say so honestly.
            Always cite your sources when possible.

            %s

            Context from documents:
            %s
            """;

    private static final String RAG_SYSTEM_PROMPT_PROJECT = """
            You are an expert code analyst. You have deep knowledge of the project codebase loaded into this knowledge base.
            When answering questions:
            - Help users understand the project architecture and structure
            - Explain how components interact
            - Suggest where to add new features based on existing patterns
            - Reference specific files and code sections from the context

            %s

            Context from the codebase:
            %s
            """;

    private static final String RAG_SYSTEM_PROMPT_BOOK = """
            You are a knowledgeable assistant with access to the full content of a book.
            Answer questions about the book's content accurately, citing specific chapters or sections when possible.
            If the context does not contain enough information to answer the question, say so honestly.

            %s

            Context from the book:
            %s
            """;

    private static final String DIRECT_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the user's question directly based on your general knowledge.
            Be concise and helpful.""";

    /**
     * Used instead of {@code systemPromptTemplate(kbType)} when retrieval confidence
     * is below {@code rag.low-confidence-threshold}: the retrieved context is probably irrelevant,
     * so refusing to answer (as the normal RAG prompts instruct) just produces an
     * unhelpful "not in the documents" response. This asks the model to fall back to
     * its own general knowledge instead, and to say so, rather than making the user
     * explicitly ask for that outside the knowledge base.
     */
    private static final String LOW_CONFIDENCE_FALLBACK_PROMPT = """
            You are a helpful assistant. The context retrieved from the knowledge base below
            has low relevance to the user's question and probably does not contain the answer.

            Do your best to answer using your own general knowledge rather than refusing.
            Only rely on the context below if it is actually relevant to the question. If you
            end up answering from general knowledge, clearly tell the user that this part of
            the answer is not from the knowledge base.

            %s

            Context from the knowledge base (may be irrelevant):
            %s
            """;

    /**
     * Fed back into the system prompt when regenerating after a failed grounding
     * check, so the retry is told what went wrong instead of just resampling the
     * same prompt.
     */
    private static final String REGENERATE_HINT = """


            注意：你上一次的回答未能被参考资料充分支持。这一次请严格依据上面的参考资料
            作答；如果参考资料确实不包含足够信息，请直接说明无法从资料中找到答案，不要
            编造。""";

    private final QueryAnalyzerService queryAnalyzer;
    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final RerankerService rerankerService;
    private final GraphService graphService;
    private final LlmService llmService;
    private final GroundingService groundingService;
    private final MetadataStore store;
    private final RagProperties props;

    public ChatService(QueryAnalyzerService queryAnalyzer, EmbeddingService embeddingService,
                       RetrievalService retrievalService, RerankerService rerankerService,
                       GraphService graphService, LlmService llmService,
                       GroundingService groundingService, MetadataStore store,
                       RagProperties props) {
        this.queryAnalyzer = queryAnalyzer;
        this.embeddingService = embeddingService;
        this.retrievalService = retrievalService;
        this.rerankerService = rerankerService;
        this.graphService = graphService;
        this.llmService = llmService;
        this.groundingService = groundingService;
        this.store = store;
        this.props = props;
    }

    /** Where SSE events go. Implemented by the controller over its {@code SseEmitter}. */
    @FunctionalInterface
    public interface EventSink {
        void send(String event, Object data) throws Exception;
    }

    /** Everything the pipeline needs about the request. */
    public record ChatContext(String question, String kbId, String sessionId, String kbType,
                              String kbName, String username) {}

    /**
     * Run the pipeline, emitting contract section 4 events into {@code sink}.
     *
     * <p>Blocking: the caller is expected to run this on a background thread and
     * complete the emitter afterwards.
     */
    public void streamChat(ChatContext ctx, EventSink sink) {
        String sessionId = ensureSession(ctx);

        try {
            sink.send("session", sessionId);
        } catch (Exception e) {
            log.debug("Client disconnected before the session event: {}", e.getMessage());
            return;
        }

        // Every independent LLM call in this exchange gets one row here -- the
        // "thinking process" shown to the user (docs/CONTRACT.md section 4,
        // event `llmCall`), persisted alongside the message so history replay
        // has it too.
        List<LlmService.LlmCallRecord> callLog = new ArrayList<>();

        try {
            List<MetadataStore.MessageRow> history = store.listMessages(sessionId, HISTORY_WINDOW);

            // "All my knowledge bases" mode: resolve the sentinel to the caller's
            // own private KBs now, so everything downstream (routing prompt,
            // retrieval) just sees a list of one or more real kb ids.
            List<String> targetKbs;
            String effectiveKbType = ctx.kbType();
            String effectiveKbName = ctx.kbName();
            if (ALL_KB_SENTINEL.equals(ctx.kbId())) {
                List<MetadataStore.KbRow> owned = store.listOwnedKbs(ctx.username());
                if (owned.size() > MAX_ALL_MODE_KBS) {
                    owned = owned.subList(0, MAX_ALL_MODE_KBS);
                }
                targetKbs = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (MetadataStore.KbRow kb : owned) {
                    targetKbs.add(kb.id());
                    names.add(kb.name());
                }
                effectiveKbType = "document";
                effectiveKbName = names.isEmpty() ? "（暂无私有知识库）" : String.join("、", names);
            } else {
                targetKbs = List.of(ctx.kbId());
            }

            // Resolved once and shared: the router decides whether the question is
            // covered by any of these, and retrieve() then searches exactly the same
            // list. Two separate lookups could disagree — the router approving a KB
            // that the cap then excludes routes rag and retrieves nothing.
            //
            // Unaffected by "all my knowledge bases" mode: the sentinel never
            // equals a real global KB id, so the exclusion is a no-op and every
            // global KB still gets merged in, same as the single-KB path.
            List<MetadataStore.KbRow> globalKbs = globalKbs(ctx.kbId());

            // --- Step 1: route + rewrite in a single LLM call ---
            long analyzeStart = System.nanoTime();
            sink.send("stage", stagePayload("analyzing", "start", null));
            QueryAnalyzerService.Analysis analysis = queryAnalyzer.analyze(
                    ctx.question(), effectiveKbType, effectiveKbName, history, globalKbs, callLog);
            sink.send("stage", stagePayload("analyzing", "done", elapsedMs(analyzeStart)));
            sink.send("llmCall", callLog.get(callLog.size() - 1));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("route", analysis.route());
            metadata.put("confidence", analysis.confidence());
            metadata.put("reason", analysis.reason());
            metadata.put("intent", analysis.intent());
            metadata.put("complexity", analysis.complexity());
            sink.send("metadata", metadata);

            if ("direct".equals(analysis.route())) {
                streamDirect(ctx, sessionId, history, sink, callLog);
                return;
            }

            // --- Steps 2-4: multi-query retrieval, dedupe, rerank ---
            long retrieveStart = System.nanoTime();
            sink.send("stage", stagePayload("retrieving", "start", null));
            List<RerankerService.Scored> candidates = retrieve(ctx, targetKbs, analysis, globalKbs);
            List<RerankerService.Scored> reranked =
                    rerankerService.rerank(analysis.resolvedQuery(), candidates);
            sink.send("stage", stagePayload("retrieving", "done", elapsedMs(retrieveStart)));

            List<String> contextParts = new ArrayList<>();
            List<Map<String, Object>> sources = new ArrayList<>();
            for (RerankerService.Scored doc : reranked) {
                contextParts.add(doc.text());
                sources.add(citation(doc));
            }
            String context = contextParts.isEmpty()
                    ? "No relevant documents found."
                    : String.join("\n\n---\n\n", contextParts);

            // --- Step 5: graph context ---
            // Skipped in "all my knowledge bases" mode: Neo4j nodes are tagged
            // by a single kb_id, so there is no single graph to query here.
            String graphContext = "";
            List<String> graphTerms = analysis.graphTerms();
            if (targetKbs.size() == 1
                    && ("graph".equals(analysis.route()) || "hybrid".equals(analysis.route()))
                    && !graphTerms.isEmpty()) {
                long graphStart = System.nanoTime();
                sink.send("stage", stagePayload("graph", "start", null));
                try {
                    graphContext = graphService.getGraphContext(ctx.kbId(), graphTerms);
                } catch (Exception e) {
                    log.warn("Failed to get graph context: {}", e.getMessage());
                }
                sink.send("stage", stagePayload("graph", "done", elapsedMs(graphStart)));
            }

            // --- Step 6: confidence ---
            double confidence = calculateConfidence(reranked, analysis.confidence());
            sink.send("confidence", confidence);

            // --- Step 7: generate, verify grounding, regenerate once if needed ---
            // Below props.lowConfidenceThreshold the retrieved context is more likely
            // noise than signal (e.g. a lexical match with no real relevance), so the
            // normal "say so honestly if the context lacks the answer" prompts just
            // produce a refusal. Swap to a prompt that allows — and asks for a
            // disclosure of — a general-knowledge answer instead.
            boolean lowConfidence = confidence < props.getLowConfidenceThreshold();
            String systemPrompt = lowConfidence
                    ? String.format(LOW_CONFIDENCE_FALLBACK_PROMPT, graphContext, context)
                    : String.format(systemPromptTemplate(effectiveKbType), graphContext, context);
            List<Message> messages = buildMessages(systemPrompt, history, ctx.question());

            long generateStart = System.nanoTime();
            sink.send("stage", stagePayload("generating", "start", null));
            LlmService.Call generateResult = callLogged("generating", messages, callLog, sink);
            String answerText = generateResult.content();

            // Grounding only makes sense when the model was told to stick to the
            // context; skip it for the low-confidence fallback, where answering
            // from general knowledge is expected and desired.
            boolean hasMaterial = !reranked.isEmpty() || !graphContext.isEmpty();
            if (hasMaterial && !lowConfidence) {
                String groundingContext = graphContext.isEmpty()
                        ? context : graphContext + "\n\n" + context;
                boolean grounded = groundingService.isGrounded(
                        ctx.question(), groundingContext, answerText, callLog);
                sink.send("llmCall", callLog.get(callLog.size() - 1));
                if (!grounded) {
                    log.info("Answer failed grounding check for session {}, regenerating once",
                            sessionId);
                    List<Message> retryMessages = buildMessages(
                            systemPrompt + REGENERATE_HINT, history, ctx.question());
                    answerText = callLogged("regenerate", retryMessages, callLog, sink).content();
                    // Retry cap is 1: whatever comes back now is final, not re-checked.
                }
            }

            // Confidence is a proxy for "the context is actually relevant", and a
            // noisy one — a chunk that merely shares a keyword with the question
            // can score high on count/route-confidence alone (see docs/CONTRACT.md
            // section 7) despite answering nothing. So also check the answer itself:
            // if the model refused even though confidence looked fine, that refusal
            // is better evidence than the score was, and it gets one general-
            // knowledge retry instead of being shown to the user as-is.
            if (!lowConfidence && looksLikeRefusal(answerText)) {
                log.info("Answer looked like a refusal despite confidence {} for session {}, "
                        + "retrying with the general-knowledge fallback prompt", confidence, sessionId);
                List<Message> fallbackMessages = buildMessages(
                        String.format(LOW_CONFIDENCE_FALLBACK_PROMPT, graphContext, context),
                        history, ctx.question());
                answerText = callLogged("regenerate", fallbackMessages, callLog, sink).content();
            }

            sink.send("stage", stagePayload("generating", "done", elapsedMs(generateStart)));

            StringBuilder answer = new StringBuilder(answerText);
            sink.send("chunk", answerText);

            if (!sources.isEmpty()) {
                sink.send("sources", sources);
            }

            List<String> suggestedQuestions =
                    queryAnalyzer.generateSuggestedQuestions(ctx.question(), answer.toString(), callLog);
            sink.send("llmCall", callLog.get(callLog.size() - 1));
            if (!suggestedQuestions.isEmpty()) {
                sink.send("suggestedQuestions", suggestedQuestions);
            }

            // --- Step 9: persist the exchange ---
            store.appendMessage(sessionId, "user", ctx.question(), null, null, null, null, null);
            store.appendMessage(sessionId, "assistant", answer.toString(),
                    sources.isEmpty() ? null : sources, confidence, analysis.route(),
                    suggestedQuestions.isEmpty() ? null : suggestedQuestions,
                    callLogAsMaps(callLog));

            sink.send("done", "");

        } catch (Exception e) {
            log.error("Chat stream error", e);
            try {
                sink.send("error", e.getMessage() == null ? "Internal error" : e.getMessage());
            } catch (Exception ignored) {
                // The client is gone; nothing left to report to.
            }
        }
    }

    private void streamDirect(ChatContext ctx, String sessionId,
                              List<MetadataStore.MessageRow> history, EventSink sink,
                              List<LlmService.LlmCallRecord> callLog)
            throws Exception {
        // A direct answer skips retrieval entirely, so its confidence is a fixed
        // high value rather than something derived from (absent) sources.
        double confidence = 0.9;
        sink.send("confidence", confidence);

        List<Message> messages = buildMessages(DIRECT_SYSTEM_PROMPT, history, ctx.question());
        long generateStart = System.nanoTime();
        sink.send("stage", stagePayload("generating", "start", null));
        StringBuilder answer = new StringBuilder();
        for (String delta : llmService.stream(messages).toIterable()) {
            answer.append(delta);
            sink.send("chunk", delta);
        }
        long generateDurationMs = elapsedMs(generateStart);
        sink.send("stage", stagePayload("generating", "done", generateDurationMs));

        // Token usage is unavailable here: this Spring AI version's OpenAI
        // client has no `stream_options` support, so a streamed response never
        // carries usage (docs/CONTRACT.md section 4). Duration is still real.
        LlmService.LlmCallRecord generateCall = new LlmService.LlmCallRecord(
                "generating", null, null, null, null, generateDurationMs, null);
        callLog.add(generateCall);
        sink.send("llmCall", generateCall);

        List<String> suggestedQuestions =
                queryAnalyzer.generateSuggestedQuestions(ctx.question(), answer.toString(), callLog);
        sink.send("llmCall", callLog.get(callLog.size() - 1));
        if (!suggestedQuestions.isEmpty()) {
            sink.send("suggestedQuestions", suggestedQuestions);
        }

        store.appendMessage(sessionId, "user", ctx.question(), null, null, null, null, null);
        store.appendMessage(sessionId, "assistant", answer.toString(), null, confidence, "direct",
                suggestedQuestions.isEmpty() ? null : suggestedQuestions, callLogAsMaps(callLog));

        sink.send("done", "");
    }

    /** Blocking call that also logs and emits its {@code llmCall} record. */
    private LlmService.Call callLogged(String step, List<Message> messages,
                                       List<LlmService.LlmCallRecord> callLog, EventSink sink)
            throws Exception {
        try {
            LlmService.Call result = llmService.callLogged(step, messages);
            callLog.add(result.record());
            sink.send("llmCall", result.record());
            return result;
        } catch (LlmService.LoggedCallException e) {
            callLog.add(e.record());
            sink.send("llmCall", e.record());
            throw e;
        }
    }

    /** {@code LlmCallRecord} list as plain maps, for persistence (Gson, not Jackson). */
    private static List<Map<String, Object>> callLogAsMaps(List<LlmService.LlmCallRecord> callLog) {
        if (callLog.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmService.LlmCallRecord record : callLog) {
            Map<String, Object> map = new HashMap<>();
            map.put("step", record.step());
            map.put("model", record.model());
            map.put("promptTokens", record.promptTokens());
            map.put("completionTokens", record.completionTokens());
            map.put("totalTokens", record.totalTokens());
            map.put("durationMs", record.durationMs());
            map.put("contentPreview", record.contentPreview());
            out.add(map);
        }
        return out;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Map<String, Object> stagePayload(String stage, String status, Long elapsedMs) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("stage", stage);
        payload.put("status", status);
        if (elapsedMs != null) {
            payload.put("elapsedMs", elapsedMs);
        }
        return payload;
    }

    /**
     * Multi-query retrieval across the target KB(s), rewrites and the global KBs,
     * deduplicated by chunk text.
     *
     * <p>{@code targetKbs} has one entry for a normal single-KB request. In "all
     * my knowledge bases" mode it has one entry per owned KB, and rewritten-query
     * multi-recall is skipped on purpose — doing it per KB would multiply the
     * search count by the KB count.
     */
    private List<RerankerService.Scored> retrieve(ChatContext ctx, List<String> targetKbs,
                                                  QueryAnalyzerService.Analysis analysis,
                                                  List<MetadataStore.KbRow> globalKbs) {
        List<RetrievalService.SearchResult> all = new ArrayList<>();

        float[] primaryEmbedding = embeddingService.embed(analysis.resolvedQuery());

        if (targetKbs.size() == 1) {
            String kbId = targetKbs.get(0);
            all.addAll(retrievalService.search(kbId, primaryEmbedding, TOP_K_PRIMARY));

            int used = 0;
            for (String rewritten : analysis.rewrittenQueries()) {
                if (used >= MAX_REWRITES) {
                    break;
                }
                if (rewritten.equals(ctx.question()) || rewritten.equals(analysis.resolvedQuery())) {
                    continue;
                }
                used++;
                try {
                    all.addAll(retrievalService.search(kbId,
                            embeddingService.embed(rewritten), TOP_K_REWRITE));
                } catch (Exception e) {
                    log.warn("Rewritten-query retrieval failed: {}", e.getMessage());
                }
            }
        } else {
            for (String targetKb : targetKbs) {
                try {
                    all.addAll(retrievalService.search(targetKb, primaryEmbedding,
                            TOP_K_ALL_MODE_PER_KB));
                } catch (Exception e) {
                    log.warn("All-KB-mode retrieval failed for {}: {}", targetKb, e.getMessage());
                }
            }
        }

        // Global KBs are shared background knowledge, merged into every search.
        for (MetadataStore.KbRow globalKb : globalKbs) {
            try {
                all.addAll(retrievalService.search(globalKb.id(), primaryEmbedding, TOP_K_GLOBAL));
            } catch (Exception e) {
                log.debug("Skipping unavailable global KB {}: {}", globalKb.id(), e.getMessage());
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<RerankerService.Scored> unique = new ArrayList<>();
        for (RetrievalService.SearchResult result : all) {
            if (seen.add(result.getText())) {
                unique.add(new RerankerService.Scored(
                        result.getText(), result.getScore(), result.getMetadata()));
            }
        }
        return unique;
    }

    /** The global KBs that will be searched — capped, so the router is told the truth. */
    private List<MetadataStore.KbRow> globalKbs(String kbId) {
        List<MetadataStore.KbRow> all = store.listGlobalKbs(kbId);
        return all.size() <= MAX_GLOBAL_KBS ? all : all.subList(0, MAX_GLOBAL_KBS);
    }

    /**
     * Confidence in the answer, from retrieval quality and route certainty.
     *
     * <p>Scores are already normalised to [0, 1] by the reranker. The formula is
     * duplicated in the Python backend — see {@code docs/CONTRACT.md} section 7.
     */
    static double calculateConfidence(List<RerankerService.Scored> reranked,
                                      double routeConfidence) {
        if (reranked.isEmpty()) {
            return 0.2;
        }

        double countFactor = Math.min(reranked.size() / 5.0, 1.0);

        int topCount = Math.min(3, reranked.size());
        double sum = 0.0;
        for (int i = 0; i < topCount; i++) {
            sum += RerankerService.clamp01(reranked.get(i).score());
        }
        double scoreFactor = Math.min((sum / topCount) / 0.8, 1.0);

        double confidence = countFactor * 0.3 + scoreFactor * 0.5
                + RerankerService.clamp01(routeConfidence) * 0.2;

        return Math.round(RerankerService.clamp01(confidence) * 100.0) / 100.0;
    }

    /**
     * Phrases that show up when the model follows the "say so honestly if the
     * context doesn't contain the answer" instruction and declines to answer.
     * Deliberately crude substring matching over a short, mixed-language list
     * rather than a second LLM call: this only needs to catch the common refusal
     * phrasings, not every one, since {@code rag.low-confidence-threshold} already catches most
     * low-relevance cases before generation even happens.
     */
    private static final List<String> REFUSAL_PHRASES = List.of(
            "无法找到", "未找到", "没有找到", "无法回答", "无法根据", "资料中没有",
            "资料里没有", "文档中没有", "不包含", "没有提到", "无法从资料中找到",
            "i cannot find", "i could not find", "i don't have information",
            "does not contain", "no relevant information");

    private static boolean looksLikeRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lower = answer.toLowerCase(java.util.Locale.ROOT);
        for (String phrase : REFUSAL_PHRASES) {
            if (lower.contains(phrase.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * System prompt, history, then the question.
     *
     * <p>The retrieved context belongs in the system message: putting it in the
     * user message makes the model treat it as part of the user's turn and pollutes
     * the conversation history.
     */
    private static List<Message> buildMessages(String systemPrompt,
                                               List<MetadataStore.MessageRow> history,
                                               String question) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (MetadataStore.MessageRow row : history) {
            messages.add("user".equals(row.role())
                    ? new UserMessage(row.content())
                    : new AssistantMessage(row.content()));
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    private static String systemPromptTemplate(String kbType) {
        if ("project".equals(kbType)) {
            return RAG_SYSTEM_PROMPT_PROJECT;
        }
        if ("book".equals(kbType)) {
            return RAG_SYSTEM_PROMPT_BOOK;
        }
        return RAG_SYSTEM_PROMPT_DOCUMENT;
    }

    private static Map<String, Object> citation(RerankerService.Scored doc) {
        Map<String, Object> metadata = doc.metadata() == null ? Map.of() : doc.metadata();
        String text = doc.text() == null ? "" : doc.text();
        Map<String, Object> source = new HashMap<>();
        source.put("text", text.length() > SOURCE_SNIPPET_CHARS
                ? text.substring(0, SOURCE_SNIPPET_CHARS) : text);
        source.put("score", Math.round(doc.score() * 10000.0) / 10000.0);
        source.put("source", metadata.getOrDefault("source", "unknown"));
        // camelCase out, snake_case in: the keys on the left are part of the
        // response contract, the ones on the right are Milvus field names.
        source.put("documentId", metadata.getOrDefault("document_id", ""));
        source.put("rerankScore", metadata.get("rerank_score"));
        return source;
    }

    // --- Sessions ---------------------------------------------------------

    /**
     * Resolve or create the session before the first event is emitted, so the id
     * sent to the client always exists in the database.
     */
    private String ensureSession(ChatContext ctx) {
        MetadataStore.SessionRow session = ctx.sessionId() == null || ctx.sessionId().isBlank()
                ? null
                : store.getSession(ctx.sessionId());

        if (session == null) {
            String sessionId = MetadataStore.newId();
            store.createSession(sessionId, deriveTitle(ctx.question()), ctx.kbId(), ctx.username());
            return sessionId;
        }
        if (session.title() == null || session.title().isEmpty()
                || "New Session".equals(session.title())) {
            store.renameSession(session.id(), deriveTitle(ctx.question()));
        }
        return session.id();
    }

    static String deriveTitle(String question) {
        if (question == null) {
            return "New Session";
        }
        String title = question.length() > 50
                ? question.substring(0, 50).strip() + "..."
                : question.strip();
        return title.isEmpty() ? "New Session" : title;
    }

    /** Full chat history for a session, oldest first. */
    public List<ChatMessage> getHistory(String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        for (MetadataStore.MessageRow row : store.listMessages(sessionId, 0)) {
            messages.add(ChatMessage.builder()
                    .role(row.role())
                    .content(row.content())
                    .sources(row.sources())
                    .suggestedQuestions(row.suggestedQuestions())
                    .llmCalls(row.llmCalls())
                    .timestamp(row.createdAt())
                    .build());
        }
        return messages;
    }

    /** Sessions visible to {@code owner}; non-admins only see their own. */
    public List<SessionResponse> listSessions(String owner, String role, String kbId) {
        Map<String, Integer> counts = store.sessionMessageCounts();
        List<SessionResponse> sessions = new ArrayList<>();
        for (MetadataStore.SessionRow row : store.listSessions(owner, role, kbId)) {
            sessions.add(toResponse(row, counts.getOrDefault(row.id(), 0)));
        }
        return sessions;
    }

    public SessionResponse createSession(String kbId, String owner, String title) {
        String resolvedTitle = title == null || title.isBlank() ? "New Session" : title;
        return toResponse(
                store.createSession(MetadataStore.newId(), resolvedTitle, kbId, owner), 0);
    }

    /** Session detail, or null when it does not exist. */
    public SessionResponse getSession(String sessionId) {
        MetadataStore.SessionRow row = store.getSession(sessionId);
        return row == null ? null : toResponse(row, store.sessionMessageCount(sessionId));
    }

    public boolean deleteSession(String sessionId) {
        return store.deleteSession(sessionId);
    }

    private static SessionResponse toResponse(MetadataStore.SessionRow row, int messageCount) {
        return SessionResponse.builder()
                .sessionId(row.id())
                .title(row.title())
                .knowledgeBaseId(row.kbId())
                .messageCount(messageCount)
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .build();
    }
}
