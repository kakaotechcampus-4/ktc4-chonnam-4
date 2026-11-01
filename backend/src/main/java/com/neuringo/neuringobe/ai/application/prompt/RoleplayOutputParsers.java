package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.ai.application.structured.output.RetryTarget;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * 역할극의 관찰 필수 출력 계약 검사. 형식은 맞지만 이 요청과 맞지 않는 출력(다른 turn_id, 허용하지 않은 코드, 전략과 다른 후보, 판정과 어긋난 전달 조건)을
 * InvalidLlmOutputException 으로 막는다. StructuredLlmExecutor 는 이를 INVALID_OUTPUT_FORMAT 으로 돌려준다(워크플로우
 * v3 11.6·11.8 의 시스템 사전 차단).
 *
 * <p>예외 메시지에는 모델 출력 내용을 넣지 않는다.
 */
final class RoleplayOutputParsers {

    private static final Pattern CODE_FENCE =
            Pattern.compile("^```[a-zA-Z]*\\s*\\n?(.*?)\\n?```$", Pattern.DOTALL);

    /** 응답 판단이 직접 내릴 수 있는 판정과 복귀 위치. RETRY_EVALUATION·SAFE_FALLBACK 은 오케스트레이터만 정한다. */
    private static final Map<EvaluationDecision, RetryTarget> MODEL_DECISIONS =
            Map.of(
                    EvaluationDecision.REGENERATE, RetryTarget.RESPONSE_GENERATION,
                    EvaluationDecision.SAFETY_REGENERATE, RetryTarget.RESPONSE_GENERATION,
                    EvaluationDecision.REANALYZE, RetryTarget.CAUSE_ANALYSIS,
                    EvaluationDecision.CONFIRM_INPUT, RetryTarget.INPUT_CONFIRMATION,
                    EvaluationDecision.SAFETY_ESCALATION, RetryTarget.SAFETY_ESCALATION);

    private RoleplayOutputParsers() {}

    static LlmOutputParser<AnalysisResult> analysis(
            ObjectMapper objectMapper,
            UUID expectedTurnId,
            Set<UUID> microGoalIds,
            GoalObservationInput observationInput,
            UUID expectedAnalysisId,
            RoleplayTurnInput.State state) {
        LlmOutputParser<AnalysisResult> delegate =
                new JacksonLlmOutputParser<>(objectMapper, AnalysisResult.class);
        Set<String> knownTurnIds = new HashSet<>();
        if (observationInput != null) {
            observationInput
                    .confirmedObservations()
                    .forEach(o -> knownTurnIds.add(o.reference().turnId().toString()));
        }
        return content -> {
            AnalysisResult result =
                    withClosingSupportLevelFilled(
                            delegate.parse(
                                    withMiscopiedIdFixed(
                                            withMiscopiedTurnIdFixed(
                                                    stripCodeFence(content),
                                                    expectedTurnId,
                                                    knownTurnIds),
                                            "analysis_id",
                                            expectedAnalysisId,
                                            Set.of())),
                            state);
            check(result.turnId().equals(expectedTurnId), "turn_id does not match the request");
            check(oneOf(RoleplayCodes.RESPONSE_ACTS, result.responseAct()), "unknown response_act");
            check(oneOf(RoleplayCodes.RELEVANCE, result.relevance()), "unknown relevance");
            check(
                    oneOf(RoleplayCodes.LEARNING_STATES, result.learningState()),
                    "unknown learning_state");
            check(oneOf(RoleplayCodes.GAP_CODES, result.primaryGap().code()), "unknown gap code");
            check(
                    oneOf(RoleplayCodes.STRATEGY_TYPES, result.nextStrategy().type()),
                    "unknown strategy type");
            check(
                    oneOf(
                            RoleplayCodes.SUPPORT_LEVELS,
                            result.nextStrategy().recommendedSupportLevel()),
                    "unknown support level");
            check(
                    microGoalIds.contains(result.nextStrategy().targetMicroGoalId()),
                    "target_micro_goal_id is not a micro goal of the scenario");
            double confidence = result.analysisConfidence();
            check(confidence >= 0.0 && confidence <= 1.0, "analysis_confidence out of range");
            checkAnalysisObservation(
                    result.goalObservation(), observationInput, expectedTurnId, expectedAnalysisId);
            try {
                RoleplaySupportDecisionRules.requireValidAnalysis(state, observationInput, result);
            } catch (IllegalArgumentException invalidSupport) {
                throw new InvalidLlmOutputException("지원 조정 또는 최대 턴 마무리가 확정 상태와 충돌합니다.", null);
            }
            return result;
        };
    }

    /**
     * 모델이 긴 id(turn_id·analysis_id·candidate_id)를 옮겨 적다 가운데 몇 글자를 틀리는 일이 잦다(앞 8자는 같고 뒤가 다른 값). 그
     * 자리에는 정해진 값 하나만 올 수 있으므로, 앞 8자가 같고 다른 알려진 id 도 아닌 값이면 잘못 옮긴 것으로 보고 모두 바로잡는다. 그 밖의 값은 그대로 두어
     * 검증에서 걸러진다.
     */
    static String withMiscopiedIdFixed(
            String content, String field, UUID expectedId, Set<String> knownIds) {
        if (content == null || expectedId == null) return content;
        String expected = expectedId.toString();
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"")
                        .matcher(content);
        if (!matcher.find()) return content;
        String written = matcher.group(1);
        if (written.equals(expected)
                || knownIds.contains(written)
                || written.length() < 8
                || !written.regionMatches(true, 0, expected, 0, 8)) {
            return content;
        }
        return content.replace("\"" + written + "\"", "\"" + expected + "\"");
    }

    static String withMiscopiedTurnIdFixed(
            String content, UUID expectedTurnId, Set<String> knownTurnIds) {
        return withMiscopiedIdFixed(content, "turn_id", expectedTurnId, knownTurnIds);
    }

    /** 마무리(CLOSE_ROLEPLAY)는 지원 수준을 현재 수준 그대로 둔다. 모델이 빠뜨리면 그 값으로 채운다. */
    private static AnalysisResult withClosingSupportLevelFilled(
            AnalysisResult result, RoleplayTurnInput.State state) {
        AnalysisResult.NextStrategy strategy = result.nextStrategy();
        if (!"CLOSE_ROLEPLAY".equals(strategy.type())
                || (strategy.recommendedSupportLevel() != null
                        && !strategy.recommendedSupportLevel().isBlank())
                || state == null) {
            return result;
        }
        return new AnalysisResult(
                result.turnId(),
                result.responseAct(),
                result.relevance(),
                result.learningState(),
                result.primaryGap(),
                new AnalysisResult.NextStrategy(
                        strategy.type(),
                        strategy.targetMicroGoalId(),
                        strategy.purpose(),
                        state.currentSupportLevel()),
                result.analysisConfidence(),
                result.goalObservation());
    }

    /** 마스코트가 아동에게 하는 말이 아니라 내부 지시처럼 쓰인 경우. 예) "넘어졌던 일을 다시 말해보게 하기", "캐릭터가 ~말하게 해줘" */
    private static final Pattern MASCOT_INSTRUCTION =
            Pattern.compile("(캐릭터|단서|유도|목표|힌트|역할극|지원 수준|(하|주|보|돌려주)기[.!…]*$)");

    static boolean looksLikeInstruction(String mascotHint) {
        return MASCOT_INSTRUCTION.matcher(mascotHint.strip()).find();
    }

    static LlmOutputParser<CandidateResponse> candidate(
            ObjectMapper objectMapper,
            UUID expectedCandidateId,
            AnalysisResult analysis,
            List<UUID> expectedFailedCandidateIds,
            Set<String> failedCandidateTexts,
            int maxTextLength,
            boolean characterVoice,
            boolean mascotHintRequired,
            boolean maximumTurnReached) {
        LlmOutputParser<CandidateResponse> delegate =
                new JacksonLlmOutputParser<>(objectMapper, CandidateResponse.class);
        Set<String> normalizedFailedTexts = new HashSet<>();
        failedCandidateTexts.forEach(text -> normalizedFailedTexts.add(normalize(text)));
        return content -> {
            CandidateResponse parsed =
                    delegate.parse(
                            withMiscopiedIdFixed(
                                    withMiscopiedIdFixed(
                                            stripCodeFence(content),
                                            "candidate_id",
                                            expectedCandidateId,
                                            Set.of()),
                                    "turn_id",
                                    analysis.turnId(),
                                    Set.of()));
            CandidateResponse result =
                    characterVoice ? withStrategyNameAsResponseTypeFixed(parsed) : parsed;
            check(
                    result.candidateId().equals(expectedCandidateId),
                    "candidate_id does not match the request");
            check(result.turnId().equals(analysis.turnId()), "turn_id does not match the analysis");
            check(
                    result.strategyUsed().equals(analysis.nextStrategy().type()),
                    "strategy_used does not follow the analysis");
            check(
                    result.targetMicroGoalId().equals(analysis.nextStrategy().targetMicroGoalId()),
                    "target_micro_goal_id does not follow the analysis");
            check(
                    result.supportLevel().equals(analysis.nextStrategy().recommendedSupportLevel()),
                    "support_level does not follow the analysis");
            check(
                    oneOf(RoleplayCodes.RESPONSE_TYPES, result.responseType()),
                    "unknown response_type");
            check(
                    !maximumTurnReached
                            || ("CLOSE_ROLEPLAY".equals(result.strategyUsed())
                                    && "CLOSING".equals(result.responseType())),
                    "최대 턴에는 마무리 응답이 필요합니다.");
            check(
                    Set.copyOf(result.previousFailedCandidateIds())
                            .equals(Set.copyOf(expectedFailedCandidateIds)),
                    "previous_failed_candidate_ids does not match the retry");

            String text = result.text().strip();
            check(text.length() <= maxTextLength, "text is too long");
            // 마스코트 힌트는 서버 규칙이 요구한 턴에만 있다.
            if (mascotHintRequired) {
                check(result.mascotHint() != null, "the mascot hint is required for this turn");
                check(result.mascotHint().length() <= maxTextLength, "the mascot hint is too long");
                check(
                        !looksLikeInstruction(result.mascotHint()),
                        "the mascot hint must speak to the child, not describe an instruction");
            } else {
                check(result.mascotHint() == null, "the mascot hint is not allowed for this turn");
            }
            long questionMarks = text.chars().filter(ch -> ch == '?').count();
            if (characterVoice
                    && RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(result.responseType())) {
                // 캐릭터 대사는 묻지 않고 반응만으로 유도할 수 있다. 묻는다면 한 번만.
                check(questionMarks <= 1, "a character line must not ask more than one question");
            } else if (RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(result.responseType())) {
                check(questionMarks == 1, "a question response must contain exactly one question");
            } else {
                check(questionMarks == 0, "a non-question response must not ask a question");
            }
            check(
                    !normalizedFailedTexts.contains(normalize(text)),
                    "text repeats a failed candidate");
            return result;
        };
    }

    /**
     * 캐릭터 대사 생성은 response_type 칸에 전략 이름(REDIRECT_TO_SCENARIO 등)을 그대로 적는 실수가 잦다. 전략과 지원 수준으로 응답 유형이
     * 하나로 정해지므로, 전략 이름이 그대로 들어온 경우에만 그 유형으로 바꾼다.
     */
    private static CandidateResponse withStrategyNameAsResponseTypeFixed(CandidateResponse result) {
        if (!result.responseType().equals(result.strategyUsed())) return result;
        String responseType =
                switch (result.strategyUsed()) {
                    case "PROBE_EVENT", "PROBE_EMOTION", "PROBE_CAUSE" ->
                            "S3".equals(result.supportLevel())
                                    ? "ANSWER_REVEAL"
                                    : "GUIDING_QUESTION";
                    case "PROBE_ACTION", "ADVANCE_GOAL" -> "GUIDING_QUESTION";
                    case "REDIRECT_TO_SCENARIO" -> "REDIRECT";
                    case "CONFIRM_UNDERSTANDING" -> "CONFIRMATION";
                    case "CLOSE_ROLEPLAY" -> "CLOSING";
                    default -> null;
                };
        if (responseType == null) return result;
        return new CandidateResponse(
                result.candidateId(),
                result.turnId(),
                result.text(),
                responseType,
                result.strategyUsed(),
                result.targetMicroGoalId(),
                result.supportLevel(),
                result.previousFailedCandidateIds(),
                result.mascotHint());
    }

    static LlmOutputParser<EvaluationResult> evaluation(
            ObjectMapper objectMapper, UUID expectedCandidateId, UUID expectedAnalysisId) {
        LlmOutputParser<EvaluationResult> delegate =
                new JacksonLlmOutputParser<>(objectMapper, EvaluationResult.class);
        return content -> {
            String repaired = stripCodeFence(content);
            repaired =
                    withMiscopiedIdFixed(repaired, "candidate_id", expectedCandidateId, Set.of());
            if (expectedAnalysisId != null) {
                repaired =
                        withMiscopiedIdFixed(repaired, "analysis_id", expectedAnalysisId, Set.of());
            }
            EvaluationResult result = delegate.parse(repaired);
            check(
                    result.candidateId().equals(expectedCandidateId),
                    "candidate_id does not match the request");
            check(
                    RoleplayCodes.FAILURE_CODES.containsAll(result.failureCodes()),
                    "unknown failure code");
            if (result.decision() == EvaluationDecision.PASS) {
                check(
                        Boolean.TRUE.equals(result.safeToSend())
                                && result.criticalFailureCount() == 0
                                && result.failureCodes().isEmpty(),
                        "PASS must be safe with no failure codes");
            } else {
                RetryTarget expectedTarget = MODEL_DECISIONS.get(result.decision());
                check(expectedTarget != null, "decision is reserved for the orchestrator");
                check(result.retryTarget() == expectedTarget, "retry_target does not fit decision");
                check(!Boolean.TRUE.equals(result.safeToSend()), "only PASS can be safe to send");
                check(!result.failureCodes().isEmpty(), "a failed evaluation needs failure codes");
            }
            long critical =
                    result.failureCodes().stream()
                            .filter(RoleplayCodes.CRITICAL_FAILURE_CODES::contains)
                            .count();
            check(
                    result.criticalFailureCount() >= critical,
                    "critical_failure_count is lower than the critical failure codes");
            var review = result.goalObservationReview();
            check(review != null, "목표 관찰 검수가 필요합니다.");
            check(expectedAnalysisId.equals(review.analysisId()), "다른 분석에 대한 목표 관찰 검수입니다.");
            if (result.decision() == EvaluationDecision.PASS) {
                check(review.supportsAnalysis(), "목표 관찰과 활동 목표 판정이 모두 승인되어야 합니다.");
            } else if (!review.supportsAnalysis()) {
                check(
                        Set.of(
                                        EvaluationDecision.REANALYZE,
                                        EvaluationDecision.CONFIRM_INPUT,
                                        EvaluationDecision.SAFETY_ESCALATION)
                                .contains(result.decision()),
                        "근거 없는 분석은 후보만 재생성할 수 없습니다.");
            }
            return result;
        };
    }

    private static void checkAnalysisObservation(
            GoalObservationOutput.Analysis analysis,
            GoalObservationInput input,
            UUID turnId,
            UUID analysisId) {
        check(analysis != null, "목표 관찰 분석이 필요합니다.");
        check(analysisId.equals(analysis.analysisId()), "목표 관찰의 분석 식별자가 일치하지 않습니다.");
        var available = new HashSet<GoalObservationOutput.Reference>();
        var positive = new HashSet<GoalObservationOutput.Reference>();
        for (var prior : input.confirmedObservations()) {
            available.add(prior.reference());
            if (prior.status() == GoalObservationOutput.Status.EXPLICIT
                    || prior.status() == GoalObservationOutput.Status.IMPLICIT) {
                positive.add(prior.reference());
            }
        }
        for (var observation : analysis.observations()) {
            var ref = observation.reference();
            check(turnId.equals(ref.turnId()), "새 관찰이 다른 턴을 참조합니다.");
            check(
                    input.contains(ref.microGoalId(), ref.criterionReference()),
                    "승인되지 않은 목표 또는 학습 증거입니다.");
            check(available.add(ref), "목표 관찰이 중복됐습니다.");
            if (observation.status() == GoalObservationOutput.Status.EXPLICIT
                    || observation.status() == GoalObservationOutput.Status.IMPLICIT) {
                positive.add(ref);
            }
        }
        var basis = analysis.activityGoal().basis();
        check(new HashSet<>(basis).size() == basis.size(), "판정 근거가 중복됐습니다.");
        check(available.containsAll(basis), "판정 근거에 없는 관찰을 참조했습니다.");
        check(
                analysis.activityGoal().decision()
                                != GoalObservationOutput.ActivityDecision.ACHIEVED
                        || !basis.isEmpty(),
                "활동 목표 달성 판정에는 관찰 근거가 필요합니다.");
        check(
                analysis.activityGoal().decision()
                                != GoalObservationOutput.ActivityDecision.ACHIEVED
                        || basis.stream().anyMatch(positive::contains),
                "활동 목표 달성 판정에는 명시적 또는 간접 표현 근거가 필요합니다.");
    }

    /** 모델이 ```json ... ``` 으로 감싸 보내는 경우만 벗긴다. */
    static String stripCodeFence(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        Matcher matcher = CODE_FENCE.matcher(trimmed);
        return matcher.matches() ? matcher.group(1).strip() : trimmed;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").strip();
    }

    private static boolean oneOf(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new InvalidLlmOutputException(message, null);
        }
    }
}
