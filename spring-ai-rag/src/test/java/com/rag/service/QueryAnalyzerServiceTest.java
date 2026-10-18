package com.rag.service;

import com.rag.store.MetadataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirror of {@code langchain-rag/tests/test_query_analyzer.py}, case for case.
 *
 * <p>No LLM is involved: {@link LlmService} is subclassed with a canned response, so
 * these tests are about parsing the model's output, not about the model. That is
 * exactly the part that has to agree across backends — the same JSON must produce
 * the same route, or the two backends send the identical question down different
 * retrieval paths and the A/B comparison is meaningless.
 */
class QueryAnalyzerServiceTest {

    /**
     * Records the prompts it was handed so the history formatting can be checked end
     * to end, and returns a canned response (or throws).
     */
    private static class FakeLlm extends LlmService {

        private final String response;
        private final RuntimeException error;
        final List<String> prompts = new ArrayList<>();

        FakeLlm(String response) {
            this(response, null);
        }

        FakeLlm(String response, RuntimeException error) {
            // The constructor only assigns its fields, so nulls are safe here as long
            // as call() never reaches the real implementation.
            super(null, null);
            this.response = response;
            this.error = error;
        }

        @Override
        public String call(String prompt) {
            prompts.add(prompt);
            if (error != null) {
                throw error;
            }
            return response;
        }
    }

    private static QueryAnalyzerService.Analysis analyze(String response) {
        return new QueryAnalyzerService(new FakeLlm(response))
                .analyze("问题", "document", "知识库", null, null);
    }

    /** A complete, well-formed model answer. */
    private static final String FULL = """
            {
              "route": "hybrid",
              "confidence": 0.9,
              "reason": "需要文档和图谱",
              "intent": "comparative",
              "resolvedQuery": "张三和李四的关系是什么",
              "rewrittenQueries": ["张三李四关系", "两人如何认识"],
              "keywords": ["张三", "李四"],
              "graphEntities": ["张三", "李四"],
              "complexity": "complex"
            }""";

    @Nested
    class Contract {

        @Test
        void aWellFormedAnswerIsParsedWhole() {
            QueryAnalyzerService.Analysis a = analyze(FULL);
            assertThat(a.route()).isEqualTo("hybrid");
            assertThat(a.confidence()).isEqualTo(0.9);
            assertThat(a.reason()).isEqualTo("需要文档和图谱");
            assertThat(a.intent()).isEqualTo("comparative");
            assertThat(a.resolvedQuery()).isEqualTo("张三和李四的关系是什么");
            assertThat(a.rewrittenQueries()).containsExactly("张三李四关系", "两人如何认识");
            assertThat(a.keywords()).containsExactly("张三", "李四");
            assertThat(a.graphEntities()).containsExactly("张三", "李四");
            assertThat(a.complexity()).isEqualTo("complex");
        }

        @Test
        void everyFieldIsPresentEvenForAnEmptyObject() {
            // Callers read all of these unconditionally, so none may be null.
            QueryAnalyzerService.Analysis a = analyze("{}");
            assertThat(a.route()).isEqualTo("rag");
            assertThat(a.confidence()).isEqualTo(0.8);
            assertThat(a.reason()).isEmpty();
            assertThat(a.intent()).isEqualTo("factual");
            assertThat(a.resolvedQuery()).isEqualTo("问题");
            assertThat(a.rewrittenQueries()).isEmpty();
            assertThat(a.keywords()).isEmpty();
            assertThat(a.graphEntities()).isEmpty();
            assertThat(a.complexity()).isEqualTo("simple");
        }

        @Test
        void onlyOneLlmCallIsMade() {
            // The whole point of merging routing and rewriting: one round-trip before
            // retrieval can start, not two.
            FakeLlm llm = new FakeLlm(FULL);
            new QueryAnalyzerService(llm).analyze("问题", "document", "知识库", null, null);
            assertThat(llm.prompts).hasSize(1);
        }
    }

    @Nested
    class CodeFence {

        @Test
        void aJsonFenceIsStripped() {
            assertThat(analyze("```json\n{\"route\": \"graph\"}\n```").route()).isEqualTo("graph");
        }

        @Test
        void aBareFenceIsStripped() {
            assertThat(analyze("```\n{\"route\": \"graph\"}\n```").route()).isEqualTo("graph");
        }

        @Test
        void surroundingWhitespaceIsIgnored() {
            assertThat(analyze("\n\n  {\"route\": \"direct\"}  \n").route()).isEqualTo("direct");
        }
    }

    @Nested
    class Route {

        @ParameterizedTest
        @ValueSource(strings = {"rag", "graph", "hybrid", "direct"})
        void everyValidRouteIsKept(String route) {
            assertThat(analyze("{\"route\": \"" + route + "\"}").route()).isEqualTo(route);
        }

        @ParameterizedTest
        @CsvSource({"RAG,rag", "Graph,graph", "DIRECT,direct", "hYbRiD,hybrid"})
        void caseIsNormalised(String given, String expected) {
            // Models answer "RAG" or "Graph" often enough to matter. The Python
            // _one_of lower-cases too; an exact-match check on either side would route
            // the same model output differently depending on which backend answered.
            assertThat(analyze("{\"route\": \"" + given + "\"}").route()).isEqualTo(expected);
        }

        @Test
        void whitespaceIsTrimmed() {
            assertThat(analyze("{\"route\": \"  hybrid  \"}").route()).isEqualTo("hybrid");
            assertThat(analyze("{\"route\": \"\\n graph \"}").route()).isEqualTo("graph");
        }

        @Test
        void anUnknownRouteFallsBackToRag() {
            // rag is the safe default throughout: it retrieves, so the answer stays
            // grounded even when the analysis was no help.
            assertThat(analyze("{\"route\": \"vector\"}").route()).isEqualTo("rag");
        }

        @Test
        void aMissingRouteFallsBackToRag() {
            assertThat(analyze("{\"confidence\": 0.9}").route()).isEqualTo("rag");
        }

        @Test
        void aNullRouteFallsBackToRag() {
            assertThat(analyze("{\"route\": null}").route()).isEqualTo("rag");
        }

        @Test
        void aNonStringRouteFallsBackToRag() {
            assertThat(analyze("{\"route\": 3}").route()).isEqualTo("rag");
            assertThat(analyze("{\"route\": [\"rag\"]}").route()).isEqualTo("rag");
        }

        @Test
        void anEmptyRouteFallsBackToRag() {
            assertThat(analyze("{\"route\": \"\"}").route()).isEqualTo("rag");
        }
    }

    @Nested
    class Confidence {

        @Test
        void aNormalValueIsKept() {
            assertThat(analyze("{\"confidence\": 0.42}").confidence()).isEqualTo(0.42);
        }

        @Test
        void zeroIsKept() {
            // Python's `float(pick(...) or 0.8)` used to turn this into 0.8 while the
            // Java side kept it, so the same answer reported two different
            // confidences depending on the backend.
            assertThat(analyze("{\"confidence\": 0}").confidence()).isEqualTo(0.0);
        }

        @Test
        void aMissingValueBecomesTheDefault() {
            assertThat(analyze("{}").confidence()).isEqualTo(0.8);
        }

        @Test
        void aNullValueBecomesTheDefault() {
            assertThat(analyze("{\"confidence\": null}").confidence()).isEqualTo(0.8);
        }

        @Test
        void aValueAboveOneIsClamped() {
            // It is rendered as a percentage bar; 420% would overflow the widget.
            assertThat(analyze("{\"confidence\": 4.2}").confidence()).isEqualTo(1.0);
        }

        @Test
        void aNegativeValueIsClamped() {
            assertThat(analyze("{\"confidence\": -1}").confidence()).isEqualTo(0.0);
        }

        @Test
        void aNumericStringIsAccepted() {
            // Some models quote the number.
            assertThat(analyze("{\"confidence\": \"0.7\"}").confidence()).isEqualTo(0.7);
        }

        @Test
        void aNonNumericStringBecomesTheDefault() {
            assertThat(analyze("{\"confidence\": \"high\"}").confidence()).isEqualTo(0.8);
        }

        @Test
        void aBooleanBecomesTheDefault() {
            // Double.parseDouble("true") throws, so Java falls back — and the Python
            // side has to skip bool explicitly, because float(True) is 1.0.
            assertThat(analyze("{\"confidence\": true}").confidence()).isEqualTo(0.8);
        }

        @Test
        void nanBecomesZero() {
            // Not legal JSON, but Gson's lenient reader accepts the bare literal (as
            // the string "NaN", which Double.parseDouble then reads back as NaN) and
            // json.loads accepts it too. Clamping NaN naively leaves NaN, which
            // serialises to invalid JSON in the SSE payload.
            assertThat(analyze("{\"confidence\": NaN}").confidence()).isEqualTo(0.0);
        }

        @Test
        void infinityIsClampedToOne() {
            // Same route as NaN: the literal arrives as "Infinity" and parseDouble
            // accepts that spelling.
            assertThat(analyze("{\"confidence\": Infinity}").confidence()).isEqualTo(1.0);
        }
    }

    @Nested
    class IntentAndComplexity {

        @ParameterizedTest
        @ValueSource(strings = {"factual", "analytical", "comparative", "procedural"})
        void everyValidIntentIsKept(String intent) {
            assertThat(analyze("{\"intent\": \"" + intent + "\"}").intent()).isEqualTo(intent);
        }

        @Test
        void anUnknownIntentFallsBackToFactual() {
            assertThat(analyze("{\"intent\": \"chitchat\"}").intent()).isEqualTo("factual");
        }

        @Test
        void anIntentIsNormalised() {
            assertThat(analyze("{\"intent\": \" Analytical \"}").intent()).isEqualTo("analytical");
        }

        @ParameterizedTest
        @ValueSource(strings = {"simple", "moderate", "complex"})
        void everyValidComplexityIsKept(String complexity) {
            assertThat(analyze("{\"complexity\": \"" + complexity + "\"}").complexity())
                    .isEqualTo(complexity);
        }

        @Test
        void anUnknownComplexityFallsBackToSimple() {
            assertThat(analyze("{\"complexity\": \"trivial\"}").complexity()).isEqualTo("simple");
        }

        @Test
        void aComplexityIsNormalised() {
            assertThat(analyze("{\"complexity\": \"COMPLEX\"}").complexity()).isEqualTo("complex");
        }
    }

    @Nested
    class ResolvedQuery {

        @Test
        void isUsedWhenPresent() {
            assertThat(analyze("{\"resolvedQuery\": \"具体的问题\"}").resolvedQuery())
                    .isEqualTo("具体的问题");
        }

        @Test
        void isTrimmed() {
            assertThat(analyze("{\"resolvedQuery\": \"  具体的问题  \"}").resolvedQuery())
                    .isEqualTo("具体的问题");
        }

        @Test
        void aBlankValueFallsBackToTheOriginalQuestion() {
            // Retrieving on an empty string would return arbitrary chunks.
            assertThat(analyze("{\"resolvedQuery\": \"   \"}").resolvedQuery()).isEqualTo("问题");
            assertThat(analyze("{\"resolvedQuery\": \"\"}").resolvedQuery()).isEqualTo("问题");
        }

        @Test
        void aMissingValueFallsBackToTheOriginalQuestion() {
            assertThat(analyze("{}").resolvedQuery()).isEqualTo("问题");
        }

        @Test
        void aNonStringValueFallsBackToTheOriginalQuestion() {
            assertThat(analyze("{\"resolvedQuery\": 42}").resolvedQuery()).isEqualTo("问题");
            assertThat(analyze("{\"resolvedQuery\": null}").resolvedQuery()).isEqualTo("问题");
        }
    }

    @Nested
    @DisplayName("the model may answer in snake_case instead of camelCase")
    class KeyCasing {

        @Test
        void resolvedQuery() {
            assertThat(analyze("{\"resolved_query\": \"具体的问题\"}").resolvedQuery())
                    .isEqualTo("具体的问题");
        }

        @Test
        void rewrittenQueries() {
            assertThat(analyze("{\"rewritten_queries\": [\"改写\"]}").rewrittenQueries())
                    .containsExactly("改写");
        }

        @Test
        void graphEntities() {
            assertThat(analyze("{\"graph_entities\": [\"张三\"]}").graphEntities())
                    .containsExactly("张三");
        }

        @Test
        void camelCaseWinsWhenBothArePresent() {
            // camelCase is what the prompt asks for, so it is the more deliberate of
            // the two.
            assertThat(analyze("{\"resolvedQuery\": \"驼峰\", \"resolved_query\": \"下划线\"}")
                    .resolvedQuery()).isEqualTo("驼峰");
        }
    }

    @Nested
    class StringLists {

        @Test
        void nonStringItemsAreDropped() {
            assertThat(analyze("{\"keywords\": [\"好的\", 42, null, {\"a\": 1}]}").keywords())
                    .containsExactly("好的");
        }

        @Test
        void blankItemsAreDropped() {
            assertThat(analyze("{\"keywords\": [\"好的\", \"\", \"   \"]}").keywords())
                    .containsExactly("好的");
        }

        @Test
        void itemsAreTrimmed() {
            assertThat(analyze("{\"keywords\": [\"  好的  \"]}").keywords()).containsExactly("好的");
        }

        @Test
        void aNonListBecomesAnEmptyList() {
            // Callers iterate over these, so a string here must not become a list of
            // characters.
            assertThat(analyze("{\"keywords\": \"好的\"}").keywords()).isEmpty();
            assertThat(analyze("{\"keywords\": null}").keywords()).isEmpty();
            assertThat(analyze("{\"keywords\": 42}").keywords()).isEmpty();
        }

        @Test
        void rewrittenQueriesAreCappedAtThree() {
            // Each one becomes a separate vector search, so this bounds the fan-out.
            assertThat(analyze("{\"rewrittenQueries\": [\"a\", \"b\", \"c\", \"d\", \"e\"]}")
                    .rewrittenQueries()).containsExactly("a", "b", "c");
        }

        @Test
        void keywordsAreCappedAtEight() {
            assertThat(analyze("{\"keywords\": [\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]}")
                    .keywords()).hasSize(8);
        }

        @Test
        void graphEntitiesAreCappedAtEight() {
            assertThat(analyze("{\"graphEntities\": "
                    + "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]}")
                    .graphEntities()).hasSize(8);
        }

        @Test
        void theCapCountsKeptItemsNotSourceItems() {
            assertThat(analyze("{\"rewrittenQueries\": [1, 2, \"a\", \"b\", \"c\", \"d\"]}")
                    .rewrittenQueries()).containsExactly("a", "b", "c");
        }
    }

    @Nested
    class GraphTerms {

        @Test
        void preferTheExtractedEntities() {
            assertThat(analyze("{\"graphEntities\": [\"张三\"], \"keywords\": [\"关键词\"]}")
                    .graphTerms()).containsExactly("张三");
        }

        @Test
        void fallBackToKeywordsWhenThereAreNoEntities() {
            // The graph lookup is worth attempting on plain keywords rather than
            // skipping it entirely.
            assertThat(analyze("{\"keywords\": [\"关键词\"]}").graphTerms())
                    .containsExactly("关键词");
        }

        @Test
        void areEmptyWhenBothAre() {
            assertThat(analyze("{}").graphTerms()).isEmpty();
        }
    }

    @Nested
    class Reason {

        @Test
        void aStringIsKept() {
            assertThat(analyze("{\"reason\": \"因为如此\"}").reason()).isEqualTo("因为如此");
        }

        @Test
        void aNonStringBecomesEmpty() {
            // It goes straight into the SSE payload the UI renders.
            assertThat(analyze("{\"reason\": 42}").reason()).isEmpty();
            assertThat(analyze("{\"reason\": null}").reason()).isEmpty();
            assertThat(analyze("{}").reason()).isEmpty();
        }
    }

    @Nested
    @DisplayName("failure falls back to RAG rather than propagating")
    class Fallback {

        private QueryAnalyzerService.Analysis failing(RuntimeException error) {
            return new QueryAnalyzerService(new FakeLlm(null, error))
                    .analyze("问题", "document", "知识库", null, null);
        }

        @Test
        void anLlmErrorFallsBack() {
            // The analysis is an optimisation. Losing it must degrade the answer, not
            // fail the request.
            QueryAnalyzerService.Analysis a = failing(new RuntimeException("boom"));
            assertThat(a.route()).isEqualTo("rag");
            assertThat(a.confidence()).isEqualTo(0.5);
            assertThat(a.resolvedQuery()).isEqualTo("问题");
            assertThat(a.reason()).contains("失败");
        }

        @Test
        void unparseableOutputFallsBack() {
            QueryAnalyzerService.Analysis a = analyze("I cannot answer that.");
            assertThat(a.route()).isEqualTo("rag");
            assertThat(a.confidence()).isEqualTo(0.5);
        }

        @Test
        void aJsonArrayFallsBack() {
            assertThat(analyze("[1, 2, 3]").route()).isEqualTo("rag");
        }

        @Test
        void aBareJsonNullFallsBack() {
            // Parses successfully, to null. A separate branch from the exception path,
            // with its own reason.
            QueryAnalyzerService.Analysis a = analyze("null");
            assertThat(a.route()).isEqualTo("rag");
            assertThat(a.confidence()).isEqualTo(0.5);
            assertThat(a.reason()).contains("格式异常");
        }

        @Test
        void anEmptyResponseFallsBack() {
            assertThat(analyze("").route()).isEqualTo("rag");
        }

        @Test
        void aNullResponseFallsBack() {
            assertThat(analyze(null).route()).isEqualTo("rag");
        }

        @Test
        void theFallbackIsSelfConsistent() {
            QueryAnalyzerService.Analysis a = QueryAnalyzerService.fallback("问题", "理由");
            assertThat(a.route()).isEqualTo("rag");
            assertThat(a.intent()).isEqualTo("factual");
            assertThat(a.complexity()).isEqualTo("simple");
            assertThat(a.resolvedQuery()).isEqualTo("问题");
            assertThat(a.rewrittenQueries()).isEmpty();
            assertThat(a.graphTerms()).isEmpty();
        }
    }

    @Nested
    class Prompt {

        private String promptFor(String kbType, String kbName,
                                 List<MetadataStore.MessageRow> history) {
            FakeLlm llm = new FakeLlm(FULL);
            new QueryAnalyzerService(llm).analyze("这个怎么用", kbType, kbName, history, null);
            return llm.prompts.get(0);
        }

        @Test
        void carriesTheQuestionAndTheKbDetails() {
            String prompt = promptFor("book", "员工手册", null);
            assertThat(prompt).contains("这个怎么用").contains("book").contains("员工手册");
        }

        @Test
        void namesAllFourRoutesSoTheModelCanPickOne() {
            // A route the prompt never mentions can only ever arrive by accident, and
            // oneOf would discard it.
            String prompt = promptFor("document", "知识库", null);
            assertThat(prompt).contains("rag").contains("graph").contains("hybrid")
                    .contains("direct");
        }

        @Test
        void aBlankKbTypeBecomesDocument() {
            assertThat(promptFor("", "知识库", null)).contains("document");
            assertThat(promptFor(null, "知识库", null)).contains("document");
            assertThat(promptFor("   ", "知识库", null)).contains("document");
        }

        @Test
        void aBlankKbNameBecomesAPlaceholder() {
            assertThat(promptFor("document", "", null)).contains("未命名");
            assertThat(promptFor("document", null, null)).contains("未命名");
        }

        private String promptForGlobalKbs(List<MetadataStore.KbRow> globalKbs) {
            FakeLlm llm = new FakeLlm(FULL);
            new QueryAnalyzerService(llm)
                    .analyze("天龙八部的释义是什么", "document", "西游记", null, globalKbs);
            return llm.prompts.get(0);
        }

        private static MetadataStore.KbRow globalKb(String name, String description) {
            return new MetadataStore.KbRow("id-" + name, name, description, "book", "admin",
                    true, 1024, "2026-01-01");
        }

        /**
         * The bug this pins down: a private KB asked about a global KB's subject was
         * routed {@code direct} — {@code reason} was literally "与西游记知识库无关，
         * 无需检索" — so retrieval never ran and the global KB was never searched,
         * even though {@code retrieve()} merges global KBs correctly. The router was
         * deciding coverage from the current KB alone because that is all the prompt
         * ever mentioned, so it was answering the question it was asked.
         */
        @Test
        void namesTheGlobalKbsSoTheRouterKnowsWhatElseIsSearchable() {
            String prompt = promptForGlobalKbs(List.of(globalKb("天龙八部", null)));
            assertThat(prompt).contains("天龙八部");
        }

        @Test
        void carriesGlobalKbDescriptionsToo() {
            // A name alone often does not say whether a question is covered: "天龙八部"
            // does, "公司文档" does not.
            String prompt = promptForGlobalKbs(
                    List.of(globalKb("公司文档", "员工手册、报销制度、年假规则")));
            assertThat(prompt).contains("公司文档：员工手册、报销制度、年假规则");
        }

        @Test
        void tellsTheModelNotToPickDirectJustBecauseTheCurrentKbIsUnrelated() {
            // Without this the model reads "与知识库无关 → direct" and applies it to
            // the current KB, which is the whole failure.
            String prompt = promptForGlobalKbs(List.of(globalKb("天龙八部", null)));
            assertThat(prompt).contains("全局知识库同样会").contains("不要因为");
        }

        @Test
        void noGlobalKbsIsRenderedAsNone() {
            assertThat(promptForGlobalKbs(null)).contains("全局知识库");
            assertThat(promptForGlobalKbs(List.of())).contains("全局知识库");
            // Rendered, not omitted: an absent section would let the model assume the
            // list is elsewhere, while "无" is a fact it can route on.
            assertThat(promptForGlobalKbs(List.of())).contains("：\n无");
        }

        @Test
        void aBlankGlobalKbNameBecomesAPlaceholder() {
            assertThat(promptForGlobalKbs(List.of(globalKb("", null)))).contains("未命名");
            assertThat(promptForGlobalKbs(List.of(globalKb(null, null)))).contains("未命名");
        }

        @Test
        void noHistoryIsRenderedAsNone() {
            assertThat(promptFor("document", "知识库", null)).contains("对话历史：\n无");
            assertThat(promptFor("document", "知识库", List.of())).contains("对话历史：\n无");
        }

        @Test
        void historyIsLabelledByRole() {
            String prompt = promptFor("document", "知识库",
                    List.of(message("user", "第一个问题"), message("assistant", "第一个答案")));
            assertThat(prompt).contains("用户: 第一个问题").contains("助手: 第一个答案");
        }

        @Test
        void onlyTheLastSixMessagesAreIncluded() {
            // The window bounds the prompt: without it a long conversation would grow
            // the analysis call without bound.
            List<MetadataStore.MessageRow> history = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                history.add(message("user", "消息" + i));
            }
            String prompt = promptFor("document", "知识库", history);

            assertThat(prompt).doesNotContain("消息0").doesNotContain("消息3");
            assertThat(prompt).contains("消息4").contains("消息9");
        }

        @Test
        void longMessagesAreTruncated() {
            String prompt = promptFor("document", "知识库",
                    List.of(message("user", "长".repeat(500))));
            assertThat(prompt).contains("长".repeat(200)).doesNotContain("长".repeat(201));
        }

        @Test
        void aNullMessageContentIsTolerated() {
            // The column is NOT NULL, but the analysis must not be what discovers a
            // violation of that.
            assertThat(promptFor("document", "知识库", List.of(message("user", null))))
                    .contains("用户: ");
        }

        @Test
        void anUnknownRoleIsLabelledAsTheAssistant() {
            assertThat(promptFor("document", "知识库", List.of(message("system", "提示"))))
                    .contains("助手: 提示");
        }

        private MetadataStore.MessageRow message(String role, String content) {
            return new MetadataStore.MessageRow(0, role, content, null, null, null,
                    "2026-01-02T03:04:05Z");
        }
    }

    @Nested
    @DisplayName("stripCodeFence, shared with GraphService")
    class StripCodeFence {

        @Test
        void leavesBareJsonAlone() {
            assertThat(GraphService.stripCodeFence("{\"a\": 1}")).isEqualTo("{\"a\": 1}");
        }

        @Test
        void stripsALabelledFence() {
            assertThat(GraphService.stripCodeFence("```json\n{\"a\": 1}\n```"))
                    .isEqualTo("{\"a\": 1}");
        }

        @Test
        void stripsAnUnlabelledFence() {
            assertThat(GraphService.stripCodeFence("```\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}");
        }

        @Test
        void handlesAnUnclosedFence() {
            assertThat(GraphService.stripCodeFence("```json\n{\"a\": 1}")).isEqualTo("{\"a\": 1}");
        }

        @Test
        void handlesAFenceWithNoNewline() {
            assertThat(GraphService.stripCodeFence("```")).isEmpty();
        }

        @Test
        void handlesNullAndEmpty() {
            assertThat(GraphService.stripCodeFence(null)).isEmpty();
            assertThat(GraphService.stripCodeFence("   ")).isEmpty();
        }

        @Test
        void keepsFencesInsideTheContent() {
            // Only the outermost wrapper is a fence; a nested one is data.
            assertThat(GraphService.stripCodeFence("```json\n{\"a\": \"``` b\"}\n```"))
                    .isEqualTo("{\"a\": \"``` b\"}");
        }
    }

    @Nested
    class Clamp01 {

        @Test
        void passesThroughTheValidRange() {
            assertThat(QueryAnalyzerService.clamp01(0.0)).isEqualTo(0.0);
            assertThat(QueryAnalyzerService.clamp01(0.5)).isEqualTo(0.5);
            assertThat(QueryAnalyzerService.clamp01(1.0)).isEqualTo(1.0);
        }

        @Test
        void clampsBothEnds() {
            assertThat(QueryAnalyzerService.clamp01(-0.1)).isEqualTo(0.0);
            assertThat(QueryAnalyzerService.clamp01(1.1)).isEqualTo(1.0);
            assertThat(QueryAnalyzerService.clamp01(Double.NEGATIVE_INFINITY)).isEqualTo(0.0);
            assertThat(QueryAnalyzerService.clamp01(Double.POSITIVE_INFINITY)).isEqualTo(1.0);
        }

        @Test
        void turnsNanIntoZero() {
            // Math.min/max would propagate it, and NaN serialises to invalid JSON.
            assertThat(QueryAnalyzerService.clamp01(Double.NaN)).isEqualTo(0.0);
        }
    }

    @Nested
    class Hyde {

        @Test
        void returnsTheGeneratedDocument() {
            assertThat(new QueryAnalyzerService(new FakeLlm("一段假设性的答案"))
                    .generateHydeDocument("问题")).isEqualTo("一段假设性的答案");
        }

        @Test
        void isTrimmed() {
            assertThat(new QueryAnalyzerService(new FakeLlm("  答案  "))
                    .generateHydeDocument("问题")).isEqualTo("答案");
        }

        @Test
        void aBlankAnswerFallsBackToTheQuestion() {
            // An empty embedding query would match arbitrary chunks.
            assertThat(new QueryAnalyzerService(new FakeLlm("   "))
                    .generateHydeDocument("问题")).isEqualTo("问题");
            assertThat(new QueryAnalyzerService(new FakeLlm(null))
                    .generateHydeDocument("问题")).isEqualTo("问题");
        }

        @Test
        void anErrorFallsBackToTheQuestion() {
            assertThat(new QueryAnalyzerService(new FakeLlm(null, new RuntimeException("boom")))
                    .generateHydeDocument("问题")).isEqualTo("问题");
        }

        @Test
        void theQuestionIsInThePrompt() {
            FakeLlm llm = new FakeLlm("答案");
            new QueryAnalyzerService(llm).generateHydeDocument("这个怎么用");
            assertThat(llm.prompts.get(0)).contains("这个怎么用");
        }
    }
}
