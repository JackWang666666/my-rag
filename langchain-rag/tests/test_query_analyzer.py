"""Tests for app.services.query_analyzer.

This module is the one place where an LLM's free-form output turns into values the
rest of the pipeline branches on — `route` picks the retrieval strategy, and
`rewritten_queries` decides how many vector searches run. A model that answers in
snake_case, wraps the JSON in a code fence, invents a route name or returns prose
instead of JSON must all end up as a usable dict rather than an exception halfway
through a streaming response.

The prompt asks for camelCase but real models drift to snake_case, so both are
accepted; `TestKeyCasing` pins that.

No LLM is called: `get_chat_model` is replaced with a fake whose `ainvoke` returns
a canned string.
"""

import asyncio

import pytest

from app.services import query_analyzer as qa


class FakeResponse:
    def __init__(self, content):
        self.content = content


class FakeModel:
    """Stands in for a LangChain chat model.

    Records the prompts it was given so tests can assert on what was sent, which
    is how the history formatting is checked end to end.
    """

    def __init__(self, content="", error=None):
        self.content = content
        self.error = error
        self.prompts = []

    async def ainvoke(self, messages):
        self.prompts.append(messages[0].content)
        if self.error is not None:
            raise self.error
        return FakeResponse(self.content)


@pytest.fixture()
def fake_llm(monkeypatch):
    """Install a fake chat model; the test sets `.content` or `.error`."""
    model = FakeModel()

    def get_chat_model(provider=None, streaming=False):
        # Analysis is a single non-streaming call; streaming it would defeat the
        # point of merging routing and rewriting into one round-trip.
        assert streaming is False
        return model

    monkeypatch.setattr(qa, "get_chat_model", get_chat_model)
    return model


def run(coroutine):
    """Drive a coroutine without pytest-asyncio, which is not a dependency."""
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coroutine)
    finally:
        loop.close()


def analyze(question="问题", **kwargs):
    return run(qa.analyze_query(question, **kwargs))


FULL_JSON = """{
    "route": "graph",
    "confidence": 0.9,
    "reason": "关系类问题",
    "intent": "analytical",
    "resolvedQuery": "张三和李四是什么关系",
    "rewrittenQueries": ["张三 李四 关系", "张三与李四的关联"],
    "keywords": ["张三", "李四"],
    "graphEntities": ["张三", "李四"],
    "complexity": "moderate"
}"""


class TestContract:
    """The Spring backend returns the same key set; the pipeline reads all of them."""

    KEYS = {
        "route",
        "confidence",
        "reason",
        "intent",
        "resolved_query",
        "rewritten_queries",
        "keywords",
        "graph_entities",
        "complexity",
    }

    def test_a_full_response_maps_to_snake_case_keys(self, fake_llm):
        fake_llm.content = FULL_JSON
        result = analyze("他们是什么关系")
        assert set(result) == self.KEYS
        assert result == {
            "route": "graph",
            "confidence": 0.9,
            "reason": "关系类问题",
            "intent": "analytical",
            "resolved_query": "张三和李四是什么关系",
            "rewritten_queries": ["张三 李四 关系", "张三与李四的关联"],
            "keywords": ["张三", "李四"],
            "graph_entities": ["张三", "李四"],
            "complexity": "moderate",
        }

    def test_the_key_set_is_the_same_on_the_fallback_path(self, fake_llm):
        fake_llm.content = "对不起，我不能回答"
        assert set(analyze()) == self.KEYS

    def test_an_empty_json_object_still_fills_every_key(self, fake_llm):
        fake_llm.content = "{}"
        assert set(analyze()) == self.KEYS


class TestCodeFence:
    def test_a_json_fence_is_stripped(self, fake_llm):
        fake_llm.content = '```json\n{"route": "direct"}\n```'
        assert analyze()["route"] == "direct"

    def test_a_bare_fence_is_stripped(self, fake_llm):
        fake_llm.content = '```\n{"route": "hybrid"}\n```'
        assert analyze()["route"] == "hybrid"

    def test_surrounding_whitespace_is_stripped(self, fake_llm):
        fake_llm.content = '\n\n  ```json\n{"route": "graph"}\n```  \n'
        assert analyze()["route"] == "graph"

    def test_an_unclosed_fence_still_parses(self, fake_llm):
        # Happens when the model hits its token limit mid-answer.
        fake_llm.content = '```json\n{"route": "direct"}'
        assert analyze()["route"] == "direct"

    def test_a_fence_with_nothing_after_it_falls_back(self, fake_llm):
        fake_llm.content = "```"
        assert analyze("原问题")["resolved_query"] == "原问题"


class TestRoute:
    @pytest.mark.parametrize("route", ["rag", "graph", "hybrid", "direct"])
    def test_each_valid_route_passes_through(self, fake_llm, route):
        fake_llm.content = '{"route": "%s"}' % route
        assert analyze()["route"] == route

    def test_the_valid_set_matches_the_other_two_layers(self):
        # QueryAnalyzerService.VALID_ROUTES in the Spring backend and `routeLabels`
        # in the frontend list exactly these; an extra route would render blank.
        assert qa.VALID_ROUTES == ("rag", "graph", "hybrid", "direct")

    @pytest.mark.parametrize(
        "route,expected",
        [
            ('"RAG"', "rag"),
            ('"Graph"', "graph"),
            ('"  hybrid  "', "hybrid"),
            ('"DIRECT"', "direct"),
        ],
    )
    def test_case_and_whitespace_are_normalised(self, fake_llm, route, expected):
        # QueryAnalyzerService.oneOf lower-cases and trims too. An exact-match
        # check here would send the same model output down a different retrieval
        # path depending on which backend answered.
        fake_llm.content = '{"route": %s}' % route
        assert analyze()["route"] == expected

    @pytest.mark.parametrize(
        "route", ['"vector"', '"rag graph"', "null", "123", "[]", '""', "{}"]
    )
    def test_anything_else_becomes_rag(self, fake_llm, route):
        # rag is the safe default: it retrieves, so the answer is grounded even
        # when the router was no help.
        fake_llm.content = '{"route": %s}' % route
        assert analyze()["route"] == "rag"

    def test_a_missing_route_becomes_rag(self, fake_llm):
        fake_llm.content = '{"intent": "factual"}'
        assert analyze()["route"] == "rag"


class TestConfidence:
    def test_a_float_passes_through(self, fake_llm):
        fake_llm.content = '{"confidence": 0.42}'
        assert analyze()["confidence"] == 0.42

    def test_a_numeric_string_is_coerced(self, fake_llm):
        fake_llm.content = '{"confidence": "0.3"}'
        assert analyze()["confidence"] == 0.3

    def test_an_integer_is_coerced(self, fake_llm):
        fake_llm.content = '{"confidence": 1}'
        assert analyze()["confidence"] == 1.0

    @pytest.mark.parametrize(
        "value,expected", [("1.5", 1.0), ("-2", 0.0), ("99", 1.0)]
    )
    def test_out_of_range_is_clamped(self, fake_llm, value, expected):
        # The frontend renders this as a percentage bar; 150% would overflow it.
        fake_llm.content = '{"confidence": %s}' % value
        assert analyze()["confidence"] == expected

    @pytest.mark.parametrize("value", ['"high"', "null", "[]", "{}"])
    def test_unusable_values_default_to_08(self, fake_llm, value):
        fake_llm.content = '{"confidence": %s}' % value
        assert analyze()["confidence"] == 0.8

    @pytest.mark.parametrize("value", ["true", "false"])
    def test_a_boolean_defaults_rather_than_becoming_1_or_0(self, fake_llm, value):
        # float(True) is 1.0, but Double.parseDouble("true") throws, so the Java
        # backend falls back. Coercing here would report 100% confidence where the
        # other backend reports 80%.
        fake_llm.content = '{"confidence": %s}' % value
        assert analyze()["confidence"] == 0.8

    def test_zero_is_kept(self, fake_llm):
        # `float(pick(...) or 0.8)` used to turn this into 0.8, while the Java
        # backend kept it — the same answer showed two different confidences.
        fake_llm.content = '{"confidence": 0}'
        assert analyze()["confidence"] == 0.0

    def test_nan_becomes_zero(self, fake_llm):
        # Not legal JSON, but json.loads accepts the bare literal and so does
        # Gson; clamping NaN naively leaves NaN, which serialises to invalid JSON
        # in the SSE payload.
        fake_llm.content = '{"confidence": NaN}'
        assert analyze()["confidence"] == 0.0


class TestIntentAndComplexity:
    @pytest.mark.parametrize(
        "intent", ["factual", "analytical", "comparative", "procedural"]
    )
    def test_valid_intents_pass_through(self, fake_llm, intent):
        fake_llm.content = '{"intent": "%s"}' % intent
        assert analyze()["intent"] == intent

    @pytest.mark.parametrize("intent", ['"opinion"', "null", "5"])
    def test_other_intents_become_factual(self, fake_llm, intent):
        fake_llm.content = '{"intent": %s}' % intent
        assert analyze()["intent"] == "factual"

    def test_intent_case_is_normalised(self, fake_llm):
        fake_llm.content = '{"intent": "Comparative"}'
        assert analyze()["intent"] == "comparative"

    @pytest.mark.parametrize("complexity", ["simple", "moderate", "complex"])
    def test_valid_complexity_passes_through(self, fake_llm, complexity):
        fake_llm.content = '{"complexity": "%s"}' % complexity
        assert analyze()["complexity"] == complexity

    @pytest.mark.parametrize("complexity", ['"very complex"', "null", "3"])
    def test_other_complexity_becomes_simple(self, fake_llm, complexity):
        fake_llm.content = '{"complexity": %s}' % complexity
        assert analyze()["complexity"] == "simple"

    def test_complexity_case_is_normalised(self, fake_llm):
        fake_llm.content = '{"complexity": "MODERATE"}'
        assert analyze()["complexity"] == "moderate"


class TestResolvedQuery:
    def test_the_resolved_query_is_used(self, fake_llm):
        fake_llm.content = '{"resolvedQuery": "张三的职位是什么"}'
        assert analyze("他的职位是什么")["resolved_query"] == "张三的职位是什么"

    def test_it_is_trimmed(self, fake_llm):
        fake_llm.content = '{"resolvedQuery": "  张三的职位  "}'
        assert analyze("他的职位")["resolved_query"] == "张三的职位"

    @pytest.mark.parametrize("value", ['""', '"   "', "null", "123", "[]"])
    def test_an_unusable_value_falls_back_to_the_question(self, fake_llm, value):
        # Retrieving on an empty string would return arbitrary chunks.
        fake_llm.content = '{"resolvedQuery": %s}' % value
        assert analyze("原始问题")["resolved_query"] == "原始问题"

    def test_a_missing_value_falls_back_to_the_question(self, fake_llm):
        fake_llm.content = "{}"
        assert analyze("原始问题")["resolved_query"] == "原始问题"


class TestKeyCasing:
    """Models drift between the camelCase the prompt asks for and snake_case."""

    def test_snake_case_keys_are_accepted(self, fake_llm):
        fake_llm.content = """{
            "resolved_query": "消解后的问题",
            "rewritten_queries": ["改写一"],
            "graph_entities": ["实体一"]
        }"""
        result = analyze()
        assert result["resolved_query"] == "消解后的问题"
        assert result["rewritten_queries"] == ["改写一"]
        assert result["graph_entities"] == ["实体一"]

    def test_camel_case_wins_when_both_are_present(self, fake_llm):
        fake_llm.content = """{
            "resolvedQuery": "camel",
            "resolved_query": "snake"
        }"""
        assert analyze()["resolved_query"] == "camel"

    def test_a_null_camel_case_key_falls_through_to_snake_case(self, fake_llm):
        # `pick` skips None, not just missing keys — a model that emits both and
        # nulls one should not lose the value.
        fake_llm.content = """{
            "resolvedQuery": null,
            "resolved_query": "snake"
        }"""
        assert analyze()["resolved_query"] == "snake"


class TestStringLists:
    def test_rewritten_queries_are_capped_at_three(self, fake_llm):
        # One vector search per query, so an over-eager model would multiply the
        # retrieval cost of every question.
        fake_llm.content = '{"rewrittenQueries": ["a", "b", "c", "d", "e"]}'
        assert analyze()["rewritten_queries"] == ["a", "b", "c"]

    def test_keywords_are_capped_at_eight(self, fake_llm):
        fake_llm.content = '{"keywords": ["%s"]}' % '", "'.join(
            "k%d" % i for i in range(12)
        )
        assert len(analyze()["keywords"]) == 8

    def test_graph_entities_are_capped_at_eight(self, fake_llm):
        fake_llm.content = '{"graphEntities": ["%s"]}' % '", "'.join(
            "e%d" % i for i in range(12)
        )
        assert len(analyze()["graph_entities"]) == 8

    def test_items_are_trimmed(self, fake_llm):
        fake_llm.content = '{"keywords": ["  张三  ", "李四"]}'
        assert analyze()["keywords"] == ["张三", "李四"]

    def test_blank_and_non_string_items_are_dropped(self, fake_llm):
        fake_llm.content = '{"keywords": ["ok", "", "   ", null, 42, {"a": 1}, ["b"]]}'
        assert analyze()["keywords"] == ["ok"]

    @pytest.mark.parametrize("value", ['"not a list"', "null", "42", "{}"])
    def test_a_non_list_becomes_an_empty_list(self, fake_llm, value):
        fake_llm.content = '{"keywords": %s}' % value
        assert analyze()["keywords"] == []

    def test_a_missing_list_becomes_an_empty_list(self, fake_llm):
        fake_llm.content = "{}"
        result = analyze()
        assert result["rewritten_queries"] == []
        assert result["keywords"] == []
        assert result["graph_entities"] == []


class TestReason:
    def test_a_string_reason_passes_through(self, fake_llm):
        fake_llm.content = '{"reason": "需要检索原文"}'
        assert analyze()["reason"] == "需要检索原文"

    @pytest.mark.parametrize("value", ["null", "42", "[]", "{}"])
    def test_a_non_string_reason_becomes_empty(self, fake_llm, value):
        # This one is rendered directly in the UI, so it has to be a string.
        fake_llm.content = '{"reason": %s}' % value
        assert analyze()["reason"] == ""


class TestFallback:
    """Analysis is best-effort: nothing here may propagate an exception.

    It runs before the first token of a streaming answer, so a raise would show
    the user a failed request rather than a slightly worse retrieval.
    """

    def test_prose_instead_of_json(self, fake_llm):
        fake_llm.content = "这个问题需要检索知识库。"
        assert analyze("原问题") == qa._fallback("原问题", "查询分析失败，使用默认RAG")

    def test_empty_content(self, fake_llm):
        fake_llm.content = ""
        assert analyze("原问题")["route"] == "rag"

    def test_truncated_json(self, fake_llm):
        fake_llm.content = '{"route": "graph", "keywords": ['
        assert analyze()["route"] == "rag"

    def test_a_json_array_at_the_top_level(self, fake_llm):
        # Valid JSON, wrong shape — it parses, so it reaches the isinstance check.
        fake_llm.content = '[{"route": "graph"}]'
        result = analyze("原问题")
        assert result == qa._fallback("原问题", "查询分析返回格式异常，使用默认RAG")

    @pytest.mark.parametrize("literal", ["null", '"text"', "42", "true"])
    def test_other_valid_json_that_is_not_an_object(self, fake_llm, literal):
        fake_llm.content = literal
        assert analyze()["route"] == "rag"

    def test_an_llm_error(self, fake_llm):
        fake_llm.error = RuntimeError("upstream 503")
        assert analyze("原问题") == qa._fallback("原问题", "查询分析失败，使用默认RAG")

    def test_a_timeout(self, fake_llm):
        fake_llm.error = asyncio.TimeoutError()
        assert analyze()["route"] == "rag"

    def test_an_unavailable_model(self, monkeypatch):
        def boom(provider=None, streaming=False):
            raise ValueError("OPENAI_API_KEY is not set")

        monkeypatch.setattr(qa, "get_chat_model", boom)
        assert analyze("原问题")["resolved_query"] == "原问题"

    def test_the_fallback_keeps_the_question_verbatim(self):
        assert qa._fallback("原问题", "理由") == {
            "route": "rag",
            "confidence": 0.5,
            "reason": "理由",
            "intent": "factual",
            "resolved_query": "原问题",
            "rewritten_queries": [],
            "keywords": [],
            "graph_entities": [],
            "complexity": "simple",
        }


class TestPrompt:
    def test_the_question_and_kb_reach_the_prompt(self, fake_llm):
        fake_llm.content = "{}"
        analyze("张三是谁", kb_type="project", kb_name="源码库")
        prompt = fake_llm.prompts[0]
        assert "张三是谁" in prompt
        assert "project" in prompt
        assert "源码库" in prompt

    def test_an_unnamed_kb_gets_a_placeholder(self, fake_llm):
        fake_llm.content = "{}"
        analyze("问题", kb_name="")
        assert "未命名" in fake_llm.prompts[0]

    def test_only_one_call_is_made(self, fake_llm):
        # The whole reason this module exists: routing and rewriting used to be
        # two serial round-trips before retrieval could start.
        fake_llm.content = FULL_JSON
        analyze("问题")
        assert len(fake_llm.prompts) == 1


class TestFormatHistory:
    def test_no_history(self):
        assert qa._format_history(None) == "无"
        assert qa._format_history([]) == "无"

    def test_roles_are_labelled_in_chinese(self):
        formatted = qa._format_history(
            [{"role": "user", "content": "你好"}, {"role": "assistant", "content": "您好"}]
        )
        assert formatted == "用户: 你好\n助手: 您好"

    def test_an_unknown_role_is_treated_as_the_assistant(self):
        assert qa._format_history([{"role": "system", "content": "x"}]) == "助手: x"

    def test_only_the_last_six_messages_are_kept(self):
        history = [{"role": "user", "content": "m%d" % i} for i in range(10)]
        formatted = qa._format_history(history)
        assert "m3" not in formatted
        assert formatted.startswith("用户: m4")
        assert len(formatted.splitlines()) == 6

    def test_long_messages_are_truncated(self):
        # The prompt is not the place to replay a 5000-character answer.
        formatted = qa._format_history([{"role": "user", "content": "x" * 500}])
        assert formatted == "用户: " + "x" * 200

    def test_a_missing_content_key_is_not_a_crash(self):
        assert qa._format_history([{"role": "user"}]) == "用户: "

    def test_a_null_content_is_not_a_crash(self):
        assert qa._format_history([{"role": "user", "content": None}]) == "用户: "

    def test_history_reaches_the_prompt(self, fake_llm):
        fake_llm.content = "{}"
        analyze("他呢", chat_history=[{"role": "user", "content": "张三是谁"}])
        assert "用户: 张三是谁" in fake_llm.prompts[0]


class TestStripCodeFence:
    def test_plain_content_is_untouched(self):
        assert qa._strip_code_fence('{"a": 1}') == '{"a": 1}'

    def test_a_language_tag_line_is_dropped(self):
        assert qa._strip_code_fence('```json\n{"a": 1}\n```') == '{"a": 1}'

    def test_the_last_fence_is_the_closing_one(self):
        # A fenced block whose body itself contains ``` must not be cut short.
        assert qa._strip_code_fence('```\n{"a": "``` x"}\n```') == '{"a": "``` x"}'


class TestHyde:
    def test_the_generated_text_is_returned_trimmed(self, fake_llm):
        fake_llm.content = "  假设性答案文本  "
        result = run(qa.generate_hyde_document("问题"))
        assert result == "假设性答案文本"
        assert "问题" in fake_llm.prompts[0]

    def test_a_failure_falls_back_to_the_question(self, fake_llm):
        # HyDE is an optimisation; embedding the raw question still works.
        fake_llm.error = RuntimeError("upstream 503")
        result = run(qa.generate_hyde_document("原问题"))
        assert result == "原问题"
