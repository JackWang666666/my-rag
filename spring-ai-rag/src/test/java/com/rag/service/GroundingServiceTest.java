package com.rag.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmService} is faked the same way {@code QueryAnalyzerServiceTest} does
 * it: no real model involved, just checking how the response gets parsed and
 * what the prompt carries.
 */
class GroundingServiceTest {

    private static class FakeLlm extends LlmService {

        private final String response;
        private final RuntimeException error;
        final List<String> prompts = new ArrayList<>();

        FakeLlm(String response) {
            this(response, null);
        }

        FakeLlm(String response, RuntimeException error) {
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

        @Override
        public Call callLogged(String step, String prompt) {
            prompts.add(prompt);
            if (error != null) {
                throw new LoggedCallException(error,
                        new LlmCallRecord(step, null, null, null, null, 0, "调用失败: " + error.getMessage()));
            }
            return new Call(response, new LlmCallRecord(step, "fake", null, null, null, 0, null));
        }
    }

    @Nested
    class Parsing {

        @Test
        void trueIsRecognised() {
            GroundingService service = new GroundingService(new FakeLlm("{\"grounded\": true}"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void falseIsRecognised() {
            GroundingService service = new GroundingService(new FakeLlm("{\"grounded\": false}"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isFalse();
        }

        @Test
        void aStringTrueIsRecognised() {
            GroundingService service =
                    new GroundingService(new FakeLlm("{\"grounded\": \"true\"}"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void aStringFalseIsRecognised() {
            GroundingService service =
                    new GroundingService(new FakeLlm("{\"grounded\": \"false\"}"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isFalse();
        }

        @Test
        void aMissingFieldFailsOpen() {
            GroundingService service = new GroundingService(new FakeLlm("{}"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }
    }

    @Nested
    class CodeFence {

        @Test
        void aJsonFenceIsStripped() {
            GroundingService service = new GroundingService(
                    new FakeLlm("```json\n{\"grounded\": false}\n```"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isFalse();
        }

        @Test
        void aBareFenceIsStripped() {
            GroundingService service = new GroundingService(
                    new FakeLlm("```\n{\"grounded\": true}\n```"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }
    }

    @Nested
    class FailOpen {

        @Test
        void anLlmErrorFailsOpen() {
            GroundingService service =
                    new GroundingService(new FakeLlm(null, new RuntimeException("boom")));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void unparseableOutputFailsOpen() {
            GroundingService service = new GroundingService(new FakeLlm("I cannot answer."));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void aJsonArrayFailsOpen() {
            GroundingService service = new GroundingService(new FakeLlm("[1, 2, 3]"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void aBareJsonNullFailsOpen() {
            GroundingService service = new GroundingService(new FakeLlm("null"));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }

        @Test
        void anEmptyResponseFailsOpen() {
            GroundingService service = new GroundingService(new FakeLlm(""));
            assertThat(service.isGrounded("问题", "参考资料", "回答", new ArrayList<>())).isTrue();
        }
    }

    @Nested
    class Truncation {

        @Test
        void aLongContextIsTruncatedTo4000Chars() {
            FakeLlm llm = new FakeLlm("{\"grounded\": true}");
            new GroundingService(llm).isGrounded("问题", "资".repeat(5000), "回答", new ArrayList<>());
            assertThat(llm.prompts.get(0)).contains("资".repeat(4000)).doesNotContain("资".repeat(4001));
        }

        @Test
        void aLongAnswerIsTruncatedTo2000Chars() {
            FakeLlm llm = new FakeLlm("{\"grounded\": true}");
            new GroundingService(llm).isGrounded("问题", "参考资料", "答".repeat(3000), new ArrayList<>());
            assertThat(llm.prompts.get(0)).contains("答".repeat(2000)).doesNotContain("答".repeat(2001));
        }

        @Test
        void theQuestionAndContextAreInThePrompt() {
            FakeLlm llm = new FakeLlm("{\"grounded\": true}");
            new GroundingService(llm).isGrounded("这个怎么用", "一些资料", "一个回答", new ArrayList<>());
            assertThat(llm.prompts.get(0))
                    .contains("这个怎么用").contains("一些资料").contains("一个回答");
        }
    }
}
