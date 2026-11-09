package com.neuringo.neuringobe.report.application;

import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.EXCLUDED_TECHNICAL_ERROR;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.REACHED;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S1;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.report.LearningReportResult;
import com.neuringo.neuringobe.ai.application.report.ReportQualityRules;
import com.neuringo.neuringobe.ai.application.report.RetryingLearningReportGenerator;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.report.domain.DifficultyBasis;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.QuizEvidence;
import com.neuringo.neuringobe.report.domain.Ratio;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.scenario.domain.GoalOutcome;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 근거 → 지표 → 프롬프트 입력 → 생성·평가 루프를 실제 프롬프트·파서로 한 번에 지나간다. LLM 만 가짜다. */
class LearningReportComposerTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void composesMetricsAndAReviewedReport() {
        ScriptedLlm llm =
                new ScriptedLlm(
                        """
                        {"summary":"상황 단서가 있을 때 감정과 사건을 연결해 말했어요.",
                         "observations":[{"text":"감정과 사건을 연결해 말했어요","evidence_ids":["E3"]}],
                         "effective_supports":[{"text":"상황 단서가 도움이 됐어요","evidence_ids":["E4"]}],
                         "difficult_points":[{"text":"상대 마음은 두 선택지를 받고 도달했어요","evidence_ids":["E5"]}]}
                        """,
                        """
                        {"decision":"PASS",
                         "scores":{"grounded":4,"non_diagnostic":4,"technical_separation":4,"actionable":4,"readable":4},
                         "failure_codes":[],"revision_instruction":null}
                        """);

        ComposedReport report = composer(llm).compose(request());

        assertThat(report.displayable()).isTrue();
        assertThat(report.metrics().quizAccuracy()).isEqualTo(new Ratio(2, 3));
        assertThat(report.metrics().roleplayScore()).isEqualTo(new Ratio(8, 12));
        assertThat(((LearningReportResult.Completed) report.result()).draft().summary())
                .startsWith("상황 단서가");

        LlmRequest generation = llm.requests.getFirst();
        assertThat(generation.traceContext().childId()).isEqualTo(CHILD_ID);
        assertThat(generation.userPrompt()).doesNotContain(CHILD_ID.toString());
        JsonNode evidence = objectMapper.readTree(generation.userPrompt()).get("evidence");
        assertThat(evidence.get(0).get("kind").asString()).isEqualTo("QUIZ_RESULT");
        assertThat(evidence.get(1).get("kind").asString()).isEqualTo("GOAL_RESULT");
        assertThat(kinds(evidence)).contains("TECHNICAL");
        // 퀴즈 결과에는 목표 상태가 없고, 목표 근거에는 목표 결과에 맞는 상태가 붙는다.
        assertThat(evidence.get(0).has("goal_status")).isFalse();
        assertThat(evidence.get(1).get("goal_status").asString()).isEqualTo("REACHED_AT_START");
        assertThat(evidence.get(4).get("goal_status").asString()).isEqualTo("EXTRA_SUPPORT");
        assertThat(evidence.get(5).get("goal_status").asString()).isEqualTo("EXCLUDED");
        assertThat(llm.requests)
                .extracting(LlmRequest::operation)
                .containsExactly(AiOperation.REPORT_GENERATION, AiOperation.REPORT_REVIEW);
    }

    @Test
    void keepsMetricsEvenWhenTheReportCannotBeShown() {
        String unknownEvidence =
                """
                {"summary":"요약이에요.","observations":[{"text":"말했어요","evidence_ids":["E99"]}],
                 "effective_supports":[],"difficult_points":[]}
                """;
        ScriptedLlm llm = new ScriptedLlm(unknownEvidence, unknownEvidence, unknownEvidence);

        ComposedReport report = composer(llm).compose(request());

        assertThat(report.displayable()).isFalse();
        assertThat(report.metrics().validGoalCount()).isEqualTo(2);
        assertThat(llm.requests)
                .extracting(LlmRequest::operation)
                .containsOnly(AiOperation.REPORT_GENERATION);
    }

    private LearningReportComposer composer(LlmProvider provider) {
        return new LearningReportComposer(
                new StructuredLlmExecutor(provider),
                new LearningReportPromptFactory(objectMapper, ReportLimits.DEFAULT),
                new RetryingLearningReportGenerator(
                        ReportQualityRules.defaults(ReportLimits.DEFAULT)),
                new LearningReportInputAssembler());
    }

    private static ReportRequest request() {
        List<GoalEvidence> goals =
                List.of(
                        new GoalEvidence(
                                new GoalOutcome(UUID.randomUUID(), S1, S1, REACHED),
                                "감정 이유",
                                List.of("상황 단서를 받은 뒤 감정과 사건을 연결함"),
                                List.of("상황 단서")),
                        new GoalEvidence(
                                new GoalOutcome(UUID.randomUUID(), S0, S2, REACHED),
                                "상대 마음",
                                List.of(),
                                List.of()),
                        new GoalEvidence(
                                new GoalOutcome(
                                        UUID.randomUUID(), S0, S0, EXCLUDED_TECHNICAL_ERROR),
                                "다시 말하기",
                                List.of(),
                                List.of()));
        ReportEvidence evidence =
                new ReportEvidence(
                        new QuizEvidence(3, 2, 1),
                        goals,
                        List.of("STT_LOW_CONFIDENCE"),
                        new DifficultyBasis(
                                DifficultyBasis.DecisionType.FOLLOW_UP,
                                ScenarioLevel.L2,
                                "SCORE_IN_KEEP_RANGE",
                                "FOLLOW_UP_ROLEPLAY_V1"));
        return new ReportRequest(REQUEST_ID, ACTIVITY_ID, null, CHILD_ID, evidence);
    }

    private static List<String> kinds(JsonNode evidence) {
        List<String> kinds = new ArrayList<>();
        evidence.forEach(node -> kinds.add(node.get("kind").asString()));
        return kinds;
    }

    /** 부를 때마다 정해 둔 응답을 차례로 돌려주고 요청을 남긴다. */
    private static final class ScriptedLlm implements LlmProvider {
        private final List<String> responses;
        private final List<LlmRequest> requests = new ArrayList<>();

        private ScriptedLlm(String... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public AiCallResult<LlmCompletion> complete(LlmRequest request) {
            requests.add(request);
            String content = responses.removeFirst();
            AiCallMetadata metadata =
                    new AiCallMetadata(
                            request.traceContext().requestId(),
                            request.operation(),
                            "fake",
                            "fake-model",
                            request.promptVersion(),
                            request.responseSchemaVersion(),
                            request.policyVersion(),
                            1,
                            request.currentAttempt(),
                            100,
                            50,
                            "stop");
            return new AiCallResult.Success<>(
                    new LlmCompletion(content, "fake", "fake-model", "stop", 100, 50), metadata);
        }
    }
}
