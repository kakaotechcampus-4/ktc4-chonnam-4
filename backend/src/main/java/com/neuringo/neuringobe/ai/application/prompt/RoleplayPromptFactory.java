package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할극 한 턴의 세 호출(원인 판단 → 후보 응답 생성 → 응답 판단) 프롬프트를 만든다. 워크플로우 v3 11·12장 기준.
 *
 * <p>시스템 프롬프트는 classpath 의 prompts/roleplay/v1/*.md 이고, 사용자 메시지는 입력 JSON 하나다. 추적
 * 식별자(request·activity· class·child·session ID)는 프롬프트에 넣지 않는다. 모델이 그대로 돌려줘야 하는 turn·candidate·micro
 * goal ID 만 넣는다.
 *
 * <p>재시도 횟수 관리와 호출 순서는 오케스트레이터의 책임이다. 이 클래스는 요청과, 그 요청에 맞는 출력인지 검사하는 parser 만 만든다.
 */
public final class RoleplayPromptFactory {

    public static final String CAUSE_ANALYSIS_PROMPT_VERSION = "roleplay-cause-analysis/v1";
    public static final String RESPONSE_GENERATION_PROMPT_VERSION =
            "roleplay-response-generation/v1";
    public static final String RESPONSE_EVALUATION_PROMPT_VERSION =
            "roleplay-response-evaluation/v1";

    public static final String ANALYSIS_SCHEMA_VERSION = "analysis-result/v1";
    public static final String CANDIDATE_SCHEMA_VERSION = "candidate-response/v1";
    public static final String EVALUATION_SCHEMA_VERSION = "evaluation-result/v1";

    /** 후보 응답 길이 상한(공백 포함). 프롬프트는 60자 이내를 요구하고, 이 값을 넘으면 형식 실패로 막는다. */
    public static final int MAX_RESPONSE_LENGTH = 80;

    private final ObjectMapper objectMapper;
    private final String policyVersion;
    private final String causeAnalysisSystemPrompt;
    private final String responseGenerationSystemPrompt;
    private final String responseEvaluationSystemPrompt;

    /**
     * @param policyVersion 지원 수준·턴 제한 정책 버전(예: SUPPORT-MVP-001). 정해지지 않았으면 null.
     */
    public RoleplayPromptFactory(ObjectMapper objectMapper, String policyVersion) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.policyVersion = policyVersion;
        this.causeAnalysisSystemPrompt = PromptTemplates.load("roleplay/v1/cause-analysis.md");
        this.responseGenerationSystemPrompt =
                PromptTemplates.load("roleplay/v1/response-generation.md");
        this.responseEvaluationSystemPrompt =
                PromptTemplates.load("roleplay/v1/response-evaluation.md");
    }

    /** A. 원인 판단. retry 는 재분석일 때만 넣는다. */
    public PreparedPrompt<AnalysisResult> causeAnalysis(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisRetry retry) {
        requireSameTurn(trace, input);

        Map<String, Object> message = turnSections(input);
        if (retry != null) {
            Map<String, Object> retrySection = new LinkedHashMap<>();
            retrySection.put("failure_codes", retry.failureCodes());
            retrySection.put("focus", retry.focus());
            retrySection.put("do_not_assume", retry.doNotAssume());
            message.put("retry", retrySection);
        }

        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.CAUSE_ANALYSIS,
                        currentAttempt,
                        causeAnalysisSystemPrompt,
                        toJson(message),
                        CAUSE_ANALYSIS_PROMPT_VERSION,
                        ANALYSIS_SCHEMA_VERSION,
                        policyVersion);
        return new PreparedPrompt<>(
                request,
                RoleplayOutputParsers.analysis(
                        objectMapper, input.learnerTurn().turnId(), microGoalIds(input)));
    }

    /** B. 후보 응답 생성. candidateId 는 호출하는 쪽이 미리 정한다. retry 는 재생성일 때만 넣는다. */
    public PreparedPrompt<CandidateResponse> responseGeneration(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisResult analysis,
            UUID candidateId,
            GenerationRetry retry) {
        requireSameTurn(trace, input);
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        if (!analysis.turnId().equals(input.learnerTurn().turnId())) {
            throw new IllegalArgumentException("analysis belongs to another turn");
        }

        Map<String, Object> message = turnSections(input);
        message.put("analysis_result", analysisSection(analysis));
        message.put("candidate_id", candidateId);
        List<UUID> failedIds = retry == null ? List.of() : retry.failedCandidateIds();
        message.put("previous_failed_candidate_ids", failedIds);
        if (retry != null) {
            Map<String, Object> retrySection = new LinkedHashMap<>();
            retrySection.put(
                    "failed_candidates",
                    retry.failedCandidates().stream()
                            .map(
                                    failed -> {
                                        Map<String, Object> entry = new LinkedHashMap<>();
                                        entry.put("candidate_id", failed.candidateId());
                                        entry.put("text", failed.text());
                                        return entry;
                                    })
                            .toList());
            retrySection.put("failure_codes", retry.failureCodes());
            Map<String, Object> revision = new LinkedHashMap<>();
            revision.put("keep", retry.revisionInstruction().keep());
            revision.put("change", retry.revisionInstruction().change());
            revision.put("avoid", retry.revisionInstruction().avoid());
            revision.put("required", retry.revisionInstruction().required());
            retrySection.put("revision_instruction", revision);
            message.put("retry", retrySection);
        }

        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.RESPONSE_GENERATION,
                        currentAttempt,
                        responseGenerationSystemPrompt,
                        toJson(message),
                        RESPONSE_GENERATION_PROMPT_VERSION,
                        CANDIDATE_SCHEMA_VERSION,
                        policyVersion);
        Set<String> failedTexts =
                retry == null
                        ? Set.of()
                        : retry.failedCandidates().stream()
                                .map(GenerationRetry.FailedCandidate::text)
                                .collect(Collectors.toSet());
        return new PreparedPrompt<>(
                request,
                RoleplayOutputParsers.candidate(
                        objectMapper,
                        candidateId,
                        analysis,
                        failedIds,
                        failedTexts,
                        MAX_RESPONSE_LENGTH));
    }

    /** 응답 판단. 같은 후보를 재평가할 때도 같은 입력으로 다시 만든다. */
    public PreparedPrompt<EvaluationResult> responseEvaluation(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisResult analysis,
            CandidateResponse candidate) {
        requireSameTurn(trace, input);
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!candidate.candidateId().equals(trace.candidateId())) {
            throw new IllegalArgumentException("trace candidateId does not match the candidate");
        }
        if (!analysis.turnId().equals(input.learnerTurn().turnId())
                || !candidate.turnId().equals(input.learnerTurn().turnId())) {
            throw new IllegalArgumentException("analysis or candidate belongs to another turn");
        }

        Map<String, Object> message = turnSections(input);
        message.put("analysis_result", analysisSection(analysis));
        message.put("candidate_response", candidateSection(candidate));

        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.RESPONSE_EVALUATION,
                        currentAttempt,
                        responseEvaluationSystemPrompt,
                        toJson(message),
                        RESPONSE_EVALUATION_PROMPT_VERSION,
                        EVALUATION_SCHEMA_VERSION,
                        policyVersion);
        return new PreparedPrompt<>(
                request, RoleplayOutputParsers.evaluation(objectMapper, candidate.candidateId()));
    }

    private Map<String, Object> turnSections(RoleplayTurnInput input) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("learning_goal", input.scenario().learningGoal());
        scenario.put("scenario_level", input.scenario().scenarioLevel());
        scenario.put("scenario_facts", input.scenario().scenarioFacts());
        scenario.put("prohibited_inferences", input.scenario().prohibitedInferences());
        scenario.put(
                "micro_goals",
                input.scenario().microGoals().stream()
                        .map(
                                goal -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("id", goal.id());
                                    entry.put("description", goal.description());
                                    entry.put("required_evidence", goal.requiredEvidence());
                                    entry.put("status", goal.status());
                                    return entry;
                                })
                        .toList());

        RoleplayTurnInput.State state = input.state();
        Map<String, Object> stateSection = new LinkedHashMap<>();
        stateSection.put("current_micro_goal_id", state.currentMicroGoalId());
        stateSection.put("current_support_level", state.currentSupportLevel());
        stateSection.put("current_micro_goal_support_turn", state.currentMicroGoalSupportTurn());
        stateSection.put("current_micro_goal_prompt_count", state.currentMicroGoalPromptCount());
        stateSection.put("answer_revealed", state.answerRevealed());
        stateSection.put("turn_count", state.turnCount());
        stateSection.put("maximum_turn_count", state.maximumTurnCount());
        stateSection.put(
                "maximum_support_turns_per_micro_goal", state.maximumSupportTurnsPerMicroGoal());

        Map<String, Object> learnerTurn = new LinkedHashMap<>();
        learnerTurn.put("turn_id", input.learnerTurn().turnId());
        learnerTurn.put("canonical_utterance", input.learnerTurn().canonicalUtterance());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("scenario", scenario);
        message.put("state", stateSection);
        message.put(
                "recent_dialogue",
                input.recentDialogue().stream()
                        .map(
                                line -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("speaker", line.speaker().name());
                                    entry.put("text", line.text());
                                    return entry;
                                })
                        .toList());
        message.put("learner_turn", learnerTurn);
        return message;
    }

    private static Map<String, Object> analysisSection(AnalysisResult analysis) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", analysis.primaryGap().code());
        gap.put("description", analysis.primaryGap().description());
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("type", analysis.nextStrategy().type());
        strategy.put("target_micro_goal_id", analysis.nextStrategy().targetMicroGoalId());
        strategy.put("purpose", analysis.nextStrategy().purpose());
        strategy.put(
                "recommended_support_level", analysis.nextStrategy().recommendedSupportLevel());
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("turn_id", analysis.turnId());
        section.put("response_act", analysis.responseAct());
        section.put("relevance", analysis.relevance());
        section.put("learning_state", analysis.learningState());
        section.put("primary_gap", gap);
        section.put("next_strategy", strategy);
        section.put("analysis_confidence", analysis.analysisConfidence());
        return section;
    }

    private static Map<String, Object> candidateSection(CandidateResponse candidate) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("candidate_id", candidate.candidateId());
        section.put("text", candidate.text());
        section.put("response_type", candidate.responseType());
        section.put("strategy_used", candidate.strategyUsed());
        section.put("target_micro_goal_id", candidate.targetMicroGoalId());
        section.put("support_level", candidate.supportLevel());
        return section;
    }

    private static Set<UUID> microGoalIds(RoleplayTurnInput input) {
        return input.scenario().microGoals().stream()
                .map(RoleplayTurnInput.MicroGoal::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireSameTurn(AiTraceContext trace, RoleplayTurnInput input) {
        Objects.requireNonNull(trace, "trace must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (!input.learnerTurn().turnId().equals(trace.turnId())) {
            throw new IllegalArgumentException("trace turnId does not match the learner turn");
        }
    }

    private String toJson(Map<String, Object> message) {
        return objectMapper.writeValueAsString(message);
    }
}
