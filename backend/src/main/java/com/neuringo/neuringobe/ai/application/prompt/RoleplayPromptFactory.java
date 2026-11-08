package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayStoredTurnSafetyLimit;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할극 한 턴의 세 호출(원인 판단 → 후보 응답 생성 → 응답 판단) 프롬프트를 만든다. 워크플로우 v3 11·12장 기준.
 *
 * <p>시스템 프롬프트는 classpath 의 prompts/roleplay 아래에 있고, 사용자 메시지는 입력 JSON 하나다. 추적 식별자(request·activity·
 * class·child·session ID)는 프롬프트에 넣지 않는다. 모델이 그대로 돌려줘야 하는 turn·candidate·micro goal ID와 v2 관찰 검수를
 * 연결하는 analysis ID만 넣는다.
 *
 * <p>재시도 횟수 관리와 호출 순서는 오케스트레이터의 책임이다. 이 클래스는 요청과, 그 요청에 맞는 출력인지 검사하는 parser 만 만든다.
 */
public final class RoleplayPromptFactory {

    public static final String CAUSE_ANALYSIS_PROMPT_VERSION = "roleplay-cause-analysis/v4";
    public static final String RESPONSE_GENERATION_PROMPT_VERSION =
            "roleplay-response-generation/v1";

    /** AI 가 등장인물을 1인칭으로 연기하는 시나리오(scenario.aiCharacter 있음)의 응답 생성. */
    public static final String CHARACTER_RESPONSE_GENERATION_PROMPT_VERSION =
            "roleplay-response-generation-character/v2";

    public static final String RESPONSE_EVALUATION_PROMPT_VERSION =
            "roleplay-response-evaluation/v8";

    /** 캐릭터 역할극의 응답 판단. 질문형 기준 없이 캐릭터 대사와 마스코트 말을 검증한다. */
    public static final String CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION =
            "roleplay-response-evaluation-character/v8";

    public static final String ANALYSIS_SCHEMA_VERSION = GoalObservationOutput.ANALYSIS_VERSION;
    public static final String CANDIDATE_SCHEMA_VERSION = "candidate-response/v1";
    public static final String EVALUATION_SCHEMA_VERSION = EvaluationOutputSchema.VERSION;

    /** 후보 응답 길이 상한(공백 포함). 프롬프트는 60자 이내를 요구하고, 이 값을 넘으면 형식 실패로 막는다. */
    public static final int MAX_RESPONSE_LENGTH = 80;

    private final ObjectMapper objectMapper;
    private final String policyVersion;
    private final String causeAnalysisSystemPrompt;
    private final String responseGenerationSystemPrompt;
    private final String characterResponseGenerationSystemPrompt;
    private final String responseEvaluationSystemPrompt;
    private final String characterCauseAnalysisRules;
    private final String characterResponseEvaluationSystemPrompt;

    /**
     * @param policyVersion 지원 수준·턴 제한 정책 버전(예: SUPPORT-MVP-001). 정해지지 않았으면 null.
     */
    public RoleplayPromptFactory(ObjectMapper objectMapper, String policyVersion) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.policyVersion = policyVersion;
        this.causeAnalysisSystemPrompt =
                PromptTemplates.load("roleplay/v2/cause-analysis.md")
                        + "\n\n"
                        + PromptTemplates.load("roleplay/v3/reanalysis.md");
        this.responseGenerationSystemPrompt =
                PromptTemplates.load("roleplay/v1/response-generation.md");
        this.characterResponseGenerationSystemPrompt =
                PromptTemplates.load("roleplay/v1/response-generation-character.md");
        this.responseEvaluationSystemPrompt =
                PromptTemplates.load("roleplay/v2/response-evaluation.md")
                        + "\n\n"
                        + PromptTemplates.load("roleplay/v3/evaluation-output-contract.md");
        this.characterCauseAnalysisRules =
                PromptTemplates.load("roleplay/v2/cause-analysis-character.md");
        this.characterResponseEvaluationSystemPrompt =
                PromptTemplates.load("roleplay/v2/response-evaluation-character.md")
                        + "\n\n"
                        + PromptTemplates.load("roleplay/v3/evaluation-output-contract.md");
    }

    /** 캐릭터 역할극이면 기본 지시문 뒤에 캐릭터 규칙을 붙인다. 안내자 역할극은 기본 지시문 그대로다. */
    private static String withCharacterRules(
            String basePrompt, String characterRules, RoleplayTurnInput input) {
        return input.scenario().aiCharacter() == null
                ? basePrompt
                : basePrompt + "\n\n" + characterRules;
    }

    /** 원인 판단과 관찰 근거를 같은 호출에서 준비한다. */
    public PreparedPrompt<AnalysisResult> causeAnalysis(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisRetry retry,
            GoalObservationInput observations) {
        requireSameTurn(trace, input);

        Map<String, Object> message = turnSections(input);
        addObservationContext(message, observations, input, trace);
        if (retry != null) {
            Map<String, Object> retrySection = new LinkedHashMap<>();
            if (retry.failedAnalysis() != null) {
                if (!retry.failedAnalysis().turnId().equals(input.learnerTurn().turnId())) {
                    throw new IllegalArgumentException("Failed analysis belongs to another turn");
                }
                retrySection.put("failed_analysis", analysisSection(retry.failedAnalysis()));
            }
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
                        withCharacterRules(
                                causeAnalysisSystemPrompt, characterCauseAnalysisRules, input),
                        toJson(message),
                        CAUSE_ANALYSIS_PROMPT_VERSION,
                        ANALYSIS_SCHEMA_VERSION,
                        policyVersion);
        return new PreparedPrompt<>(
                request,
                RoleplayOutputParsers.analysis(
                        objectMapper,
                        input.learnerTurn().turnId(),
                        microGoalIds(input),
                        observations,
                        trace.analysisId(),
                        input.state()));
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
        return responseGeneration(
                trace, attempts, currentAttempt, input, analysis, candidateId, retry, null);
    }

    public PreparedPrompt<CandidateResponse> responseGeneration(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisResult analysis,
            UUID candidateId,
            GenerationRetry retry,
            GenerationOutputRetry outputRetry) {
        requireSameTurn(trace, input);
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        if (!analysis.turnId().equals(input.learnerTurn().turnId())) {
            throw new IllegalArgumentException("analysis belongs to another turn");
        }
        requireAnalysisIdentity(trace, analysis);

        Map<String, Object> message = turnSections(input);
        message.put("analysis_result", analysisSection(analysis));
        if (input.scenario().aiCharacter() != null)
            message.put("character_response_contract", CharacterResponseContract.from(analysis));
        message.put("candidate_id", candidateId);
        Optional<MascotHintRule.Reason> mascotHint = MascotHintRule.decide(input, analysis);
        mascotHint.ifPresent(reason -> message.put("mascot_hint", Map.of("reason", reason.name())));
        List<UUID> failedIds = retry == null ? List.of() : retry.failedCandidateIds();
        message.put("previous_failed_candidate_ids", failedIds);
        if (outputRetry != null)
            message.put(
                    "output_retry",
                    Map.of(
                            "error_code",
                            outputRetry.name(),
                            "correction",
                            outputRetry.correction()));
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

        boolean characterVoice = input.scenario().aiCharacter() != null;
        LlmRequest request =
                new LlmRequest(
                        trace,
                        attempts,
                        AiOperation.RESPONSE_GENERATION,
                        currentAttempt,
                        characterVoice
                                ? characterResponseGenerationSystemPrompt
                                : responseGenerationSystemPrompt,
                        toJson(message),
                        characterVoice
                                ? CHARACTER_RESPONSE_GENERATION_PROMPT_VERSION
                                : RESPONSE_GENERATION_PROMPT_VERSION,
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
                        MAX_RESPONSE_LENGTH,
                        characterVoice,
                        mascotHint.isPresent(),
                        RoleplayStoredTurnSafetyLimit.reached(input.state().turnCount())
                                || (input.state().learningTurnCount() != null
                                        && input.state().learningTurnCount()
                                                >= input.state().maximumTurnCount()
                                        && !analysis.learningState().equals("NOT_ASSESSABLE"))));
    }

    /** 같은 분석·관찰과 후보를 함께 검수한다. */
    public PreparedPrompt<EvaluationResult> responseEvaluation(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisResult analysis,
            CandidateResponse candidate,
            GoalObservationInput observations) {
        return responseEvaluation(
                trace, attempts, currentAttempt, input, analysis, candidate, observations, null);
    }

    public PreparedPrompt<EvaluationResult> responseEvaluation(
            AiTraceContext trace,
            AiAttemptContext attempts,
            int currentAttempt,
            RoleplayTurnInput input,
            AnalysisResult analysis,
            CandidateResponse candidate,
            GoalObservationInput observations,
            EvaluationRetry retry) {
        requireSameTurn(trace, input);
        Objects.requireNonNull(analysis, "analysis must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!candidate.candidateId().equals(trace.candidateId())) {
            throw new IllegalArgumentException("trace candidateId does not match the candidate");
        }
        boolean characterVoice = input.scenario().aiCharacter() != null;
        if (!analysis.turnId().equals(input.learnerTurn().turnId())
                || !candidate.turnId().equals(input.learnerTurn().turnId())) {
            throw new IllegalArgumentException("analysis or candidate belongs to another turn");
        }
        requireAnalysisIdentity(trace, analysis);

        Map<String, Object> message = turnSections(input);
        addObservationContext(message, observations, input, trace);
        message.put("analysis_result", analysisSection(analysis));
        if (input.scenario().aiCharacter() != null)
            message.put("character_response_contract", CharacterResponseContract.from(analysis));
        var scoped =
                new ScopedAnalysisContext(
                        objectMapper.valueToTree(message), input, analysis, observations);
        var schema =
                new EvaluationOutputSchema(
                        scoped,
                        candidate,
                        characterVoice ? CharacterResponseContract.from(analysis) : null);
        var modelMessage = (tools.jackson.databind.node.ObjectNode) scoped.input();
        modelMessage.set(
                "candidate_response", objectMapper.valueToTree(candidateSection(candidate)));
        if (retry != null) {
            Map<String, Object> retrySection = new LinkedHashMap<>();
            retrySection.put("error_code", retry.name());
            retrySection.put("correction", retry.correction());
            if (retry == EvaluationRetry.UNKNOWN_FAILURE_CODE) {
                retrySection.put(
                        "allowed_failure_codes", EvaluationOutputSchema.modelFailureCodes());
            }
            modelMessage.set("retry", objectMapper.valueToTree(retrySection));
        }

        LlmRequest request =
                new LlmRequest(
                                trace,
                                attempts,
                                AiOperation.RESPONSE_EVALUATION,
                                currentAttempt,
                                characterVoice
                                        ? characterResponseEvaluationSystemPrompt
                                        : responseEvaluationSystemPrompt,
                                objectMapper.writeValueAsString(modelMessage),
                                characterVoice
                                        ? CHARACTER_RESPONSE_EVALUATION_PROMPT_VERSION
                                        : RESPONSE_EVALUATION_PROMPT_VERSION,
                                EVALUATION_SCHEMA_VERSION,
                                policyVersion)
                        .withResponseJsonSchema(schema.jsonSchema());
        return new PreparedPrompt<>(
                request,
                RoleplayOutputParsers.nativeEvaluation(
                        objectMapper, candidate.candidateId(), trace.analysisId(), scoped, schema));
    }

    private static void addObservationContext(
            Map<String, Object> message,
            GoalObservationInput observations,
            RoleplayTurnInput input,
            AiTraceContext trace) {
        Objects.requireNonNull(observations, "승인된 목표 관찰 입력이 필요합니다.");
        Objects.requireNonNull(trace.analysisId(), "목표 관찰 분석 식별자가 필요합니다.");
        observations.requireMatches(input);
        message.put("analysis_id", trace.analysisId());
        message.put("goal_observation_context", observations);
        message.put(
                "independence_review_context",
                RoleplayIndependenceEvidence.confirmed(observations));
        if (input.scenario().aiCharacter() != null) {
            message.put("goal_evidence_so_far", evidenceSoFar(observations, input));
        }
    }

    /**
     * 이전 턴까지 확정된 관찰을 목표별로 요약한다. 확정 관찰은 턴마다 모든 목표의 NOT_EXPRESSED 가 섞인 긴 목록이라, 캐릭터 역할극에서 이미 증거가 모인
     * 목표를 놓치고 대화를 끝내지 못하는 일이 잦다. 목표마다 가장 강한 증거 하나와 그 reference 를 준다.
     */
    private static List<Map<String, Object>> evidenceSoFar(
            GoalObservationInput observations, RoleplayTurnInput input) {
        return input.scenario().microGoals().stream()
                .map(
                        goal -> {
                            var best =
                                    observations.confirmedObservations().stream()
                                            .filter(
                                                    o ->
                                                            o.reference()
                                                                    .microGoalId()
                                                                    .equals(goal.id()))
                                            .filter(
                                                    o ->
                                                            o.status()
                                                                            == GoalObservationOutput
                                                                                    .Status.EXPLICIT
                                                                    || o.status()
                                                                            == GoalObservationOutput
                                                                                    .Status
                                                                                    .IMPLICIT)
                                            .min(
                                                    java.util.Comparator.comparing(
                                                            GoalObservationOutput.Observation
                                                                    ::status));
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("micro_goal_id", goal.id());
                            entry.put("evidence", best.map(o -> o.status().name()).orElse("NONE"));
                            entry.put(
                                    "reference",
                                    best.map(GoalObservationOutput.Observation::reference)
                                            .orElse(null));
                            return entry;
                        })
                .toList();
    }

    private static void requireAnalysisIdentity(AiTraceContext trace, AnalysisResult analysis) {
        Objects.requireNonNull(analysis.goalObservation(), "검수할 목표 관찰 분석이 필요합니다.");
        if (!analysis.goalObservation().analysisId().equals(trace.analysisId())) {
            throw new IllegalArgumentException("다른 분석의 목표 관찰 결과입니다.");
        }
    }

    private Map<String, Object> turnSections(RoleplayTurnInput input) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("learning_goal", input.scenario().learningGoal());
        scenario.put("scenario_level", input.scenario().scenarioLevel());
        scenario.put("scenario_facts", input.scenario().scenarioFacts());
        scenario.put("prohibited_inferences", input.scenario().prohibitedInferences());
        RoleplayTurnInput.AiCharacter character = input.scenario().aiCharacter();
        if (character != null) {
            Map<String, Object> aiCharacter = new LinkedHashMap<>();
            aiCharacter.put("id", character.id());
            aiCharacter.put("role", character.role());
            aiCharacter.put("personality", character.personality());
            aiCharacter.put("speech_style", character.speechStyle());
            scenario.put("ai_character", aiCharacter);
        }
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
        stateSection.put(
                "maximum_stored_turn_count", RoleplayStoredTurnSafetyLimit.MAXIMUM_STORED_TURNS);
        stateSection.put("learning_turn_count", state.learningTurnCount());
        stateSection.put(
                "current_micro_goal_learning_support_turn",
                state.currentMicroGoalLearningSupportTurn());
        stateSection.put("maximum_turn_count", state.maximumTurnCount());
        stateSection.put(
                "maximum_support_turns_per_micro_goal", state.maximumSupportTurnsPerMicroGoal());

        Map<String, Object> learnerTurn = new LinkedHashMap<>();
        learnerTurn.put("turn_id", input.learnerTurn().turnId());
        learnerTurn.put("canonical_utterance", input.learnerTurn().canonicalUtterance());

        var quality = input.learnerTurn().inputQuality();
        if (quality != null) {
            learnerTurn.put("input_type", quality.inputType());
            Map<String, Object> qualitySection = new LinkedHashMap<>();
            qualitySection.put("status", "ANALYZABLE");
            qualitySection.put("stt_confidence", quality.sttConfidence());
            qualitySection.put("stt_confidence_threshold", quality.sttConfidenceThreshold());
            if (quality.recoverySnapshot() != null) {
                qualitySection.put(
                        "low_confidence_retry_count", quality.recoverySnapshot().failureCount());
                qualitySection.put(
                        "text_input_suggested", quality.recoverySnapshot().textRecommended());
            }
            qualitySection.put("normalization_confidence", quality.normalizationConfidence());
            qualitySection.put("meaning_preserved", true);
            learnerTurn.put("input_quality", qualitySection);
            learnerTurn.put(
                    "safety_result",
                    Map.of(
                            "status",
                            "SAFE",
                            "personal_information_masked",
                            quality.personalInformationMasked()));
        }

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
        section.put("goal_observation", analysis.goalObservation());
        if (analysis.supportAdjustment() != null)
            section.put("server_support_adjustment", analysis.supportAdjustment());
        return section;
    }

    private static Map<String, Object> candidateSection(CandidateResponse candidate) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("candidate_id", candidate.candidateId());
        section.put("text", candidate.text());
        if (candidate.mascotHint() != null) section.put("mascot_hint", candidate.mascotHint());
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
