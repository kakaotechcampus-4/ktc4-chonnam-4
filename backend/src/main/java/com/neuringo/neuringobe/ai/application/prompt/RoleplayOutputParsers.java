package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
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
 * 역할극 프롬프트 v1 출력의 계약 검사. 형식은 맞지만 이 요청과 맞지 않는 출력(다른 turn_id, 허용하지 않은 코드, 전략과 다른 후보, 판정과 어긋난 전달 조건)을
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
            ObjectMapper objectMapper, UUID expectedTurnId, Set<UUID> microGoalIds) {
        LlmOutputParser<AnalysisResult> delegate =
                new JacksonLlmOutputParser<>(objectMapper, AnalysisResult.class);
        return content -> {
            AnalysisResult result = delegate.parse(stripCodeFence(content));
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
            return result;
        };
    }

    static LlmOutputParser<CandidateResponse> candidate(
            ObjectMapper objectMapper,
            UUID expectedCandidateId,
            AnalysisResult analysis,
            List<UUID> expectedFailedCandidateIds,
            Set<String> failedCandidateTexts,
            int maxTextLength) {
        LlmOutputParser<CandidateResponse> delegate =
                new JacksonLlmOutputParser<>(objectMapper, CandidateResponse.class);
        Set<String> normalizedFailedTexts = new HashSet<>();
        failedCandidateTexts.forEach(text -> normalizedFailedTexts.add(normalize(text)));
        return content -> {
            CandidateResponse result = delegate.parse(stripCodeFence(content));
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
                    Set.copyOf(result.previousFailedCandidateIds())
                            .equals(Set.copyOf(expectedFailedCandidateIds)),
                    "previous_failed_candidate_ids does not match the retry");

            String text = result.text().strip();
            check(text.length() <= maxTextLength, "text is too long");
            long questionMarks = text.chars().filter(ch -> ch == '?').count();
            if (RoleplayCodes.QUESTION_RESPONSE_TYPES.contains(result.responseType())) {
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

    static LlmOutputParser<EvaluationResult> evaluation(
            ObjectMapper objectMapper, UUID expectedCandidateId) {
        LlmOutputParser<EvaluationResult> delegate =
                new JacksonLlmOutputParser<>(objectMapper, EvaluationResult.class);
        return content -> {
            EvaluationResult result = delegate.parse(stripCodeFence(content));
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
            return result;
        };
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
