package com.neuringo.neuringobe.ai.application.prompt;

import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.goodDraft;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewDecision;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 리포트 프롬프트 v1 의 입력 모양과 출력 형식 검사. */
class LearningReportPromptFactoryTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final LearningReportPromptFactory factory =
            new LearningReportPromptFactory(objectMapper, ReportLimits.DEFAULT);
    private final AiTraceContext trace =
            new AiTraceContext(
                    REQUEST_ID, ACTIVITY_ID, null, CHILD_ID, null, null, null, null, null, null);
    private final AiAttemptContext attempts = new AiAttemptContext(0, 1, 0);

    @Test
    void generationSendsEvidenceMetricsAndLimitsWithoutTraceIds() {
        LlmRequest request = factory.generation(trace, attempts, 1, input(), null).request();
        JsonNode message = objectMapper.readTree(request.userPrompt());

        assertThat(request.operation()).isEqualTo(AiOperation.REPORT_GENERATION);
        assertThat(request.promptVersion()).isEqualTo("report-generation/v3");
        assertThat(request.systemPrompt()).contains("리포트 작성 에이전트");
        assertThat(message.get("evidence")).hasSize(4);
        assertThat(message.get("evidence").get(3).get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(message.get("evidence").get(3).has("goal")).isFalse();
        assertThat(message.get("limits").get("summary_max_chars").asInt()).isEqualTo(120);
        assertThat(message.get("difficulty").has("policy_version")).isFalse();
        assertThat(message.has("retry")).isFalse();
        assertThat(request.userPrompt())
                .doesNotContain(REQUEST_ID.toString(), ACTIVITY_ID.toString(), CHILD_ID.toString());
    }

    @Test
    void generationRetryCarriesPreviousDraftCodesAndInstruction() {
        ReportRevision revision =
                new ReportRevision(
                        goodDraft(),
                        List.of(ReportCodes.TOO_LONG),
                        new RevisionInstruction(List.of(), List.of("짧게 쓴다"), List.of(), List.of()));

        JsonNode retry =
                objectMapper
                        .readTree(
                                factory.generation(trace, attempts, 2, input(), revision)
                                        .request()
                                        .userPrompt())
                        .get("retry");

        assertThat(retry.get("previous_draft").get("summary").asString())
                .isEqualTo(goodDraft().summary());
        assertThat(retry.get("failure_codes").get(0).asString()).isEqualTo("TOO_LONG");
        assertThat(retry.get("revision_instruction").get("change").get(0).asString())
                .isEqualTo("짧게 쓴다");
    }

    @Test
    void reviewSendsTheDraftToJudge() {
        LlmRequest request = factory.review(trace, attempts, 1, input(), goodDraft()).request();

        assertThat(request.operation()).isEqualTo(AiOperation.REPORT_REVIEW);
        assertThat(request.systemPrompt()).contains("리포트 평가 에이전트");
        assertThat(objectMapper.readTree(request.userPrompt()).get("draft").get("observations"))
                .hasSize(1);
    }

    @Test
    void reportCallsRequireChildInTrace() {
        AiTraceContext withoutChild =
                new AiTraceContext(
                        REQUEST_ID, ACTIVITY_ID, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> factory.generation(withoutChild, attempts, 1, input(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void draftParserAcceptsCodeFenceAndRejectsBlankOrMissingSections() {
        var parser = factory.generation(trace, attempts, 1, input(), null).parser();

        ReportDraft draft =
                parser.parse(
                        """
                        ```json
                        {"summary":"요약이에요.","observations":[{"text":"말했어요","evidence_ids":["E1"]}],
                         "effective_supports":[],"difficult_points":[]}
                        ```""");
        assertThat(draft.observations().getFirst().evidenceIds()).containsExactly("E1");

        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        "{\"summary\":\" \",\"observations\":[],\"effective_supports\":[],\"difficult_points\":[]}"))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"요약\",\"observations\":[]}"))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void reviewParserRejectsInconsistentDecisionsAndCoercedScores() {
        var parser = factory.review(trace, attempts, 1, input(), goodDraft()).parser();

        ReportReview review = parser.parse(review("PASS", "4", "[]", "null"));
        assertThat(review.decision()).isEqualTo(ReportReviewDecision.PASS);

        assertThatThrownBy(() -> parser.parse(review("PASS", "4", "[\"TOO_LONG\"]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parser.parse(review("REVISE", "2", "[]", instruction())))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(
                        () -> parser.parse(review("REVISE", "2", "[\"MADE_UP\"]", instruction())))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parser.parse(review("REVISE", "2", "[\"TOO_LONG\"]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parser.parse(review("PASS", "\"4\"", "[]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
        assertThatThrownBy(() -> parser.parse(review("PASS", "5", "[]", "null")))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void reviewParserReshapesRevisionInstructionSentAsText() {
        var parser = factory.review(trace, attempts, 1, input(), goodDraft()).parser();

        // 2026-10-29 실측에서 모든 평가 모델이 보낸 두 모양: 문장 하나, 배열 대신 문자열.
        ReportReview sentence =
                parser.parse(
                        review(
                                "REVISE",
                                "2",
                                "[\"UNGROUNDED_CLAIM\"]",
                                "\"keep: 관찰 change: 근거만 쓴다\""));
        assertThat(sentence.revisionInstruction().change())
                .containsExactly("keep: 관찰 change: 근거만 쓴다");
        assertThat(sentence.revisionInstruction().keep()).isEmpty();

        ReportReview fields =
                parser.parse(
                        review(
                                "REVISE",
                                "2",
                                "[\"UNGROUNDED_CLAIM\"]",
                                "{\"keep\":\"관찰 항목\",\"change\":[\"근거만 쓴다\"],\"avoid\":\"숫자\",\"required\":[]}"));
        assertThat(fields.revisionInstruction().keep()).containsExactly("관찰 항목");
        assertThat(fields.revisionInstruction().avoid()).containsExactly("숫자");

        // 판정·점수는 그대로 엄격하다.
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        review(
                                                "REVISE",
                                                "\"2\"",
                                                "[\"UNGROUNDED_CLAIM\"]",
                                                "\"고친다\"")))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void reviewParserReadsItemChecksAndRejectsUnknownSections() {
        var parser = factory.review(trace, attempts, 1, input(), goodDraft()).parser();

        ReportReview review =
                parser.parse(
                        """
                        {"item_checks":[{"section":"difficult_points","index":0,"grounded":true,"right_section":false}],
                         "decision":"PASS",
                         "scores":{"grounded":4,"non_diagnostic":4,"technical_separation":4,"actionable":4,"readable":4},
                         "failure_codes":[],"revision_instruction":null}
                        """);
        assertThat(review.itemChecks()).hasSize(1);
        assertThat(review.everyItemChecked()).isFalse();

        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        """
                                        {"item_checks":[{"section":"summary","index":0,"grounded":true,"right_section":true}],
                                         "decision":"PASS",
                                         "scores":{"grounded":4,"non_diagnostic":4,"technical_separation":4,"actionable":4,"readable":4},
                                         "failure_codes":[],"revision_instruction":null}
                                        """))
                .isInstanceOf(InvalidLlmOutputException.class);
    }

    private static String review(String decision, String score, String codes, String instruction) {
        return """
                {"decision":"%s",
                 "scores":{"grounded":%s,"non_diagnostic":4,"technical_separation":4,"actionable":4,"readable":4},
                 "failure_codes":%s,"revision_instruction":%s}
                """
                .formatted(decision, score, codes, instruction);
    }

    private static String instruction() {
        return "{\"keep\":[],\"change\":[\"짧게 쓴다\"],\"avoid\":[],\"required\":[]}";
    }
}
