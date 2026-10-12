package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.util.EnumSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 키 없이 측정 실행기를 끝까지 돌려 보는 가짜 모델. 요청 JSON 을 읽어 출력 검사를 통과하는 답을 만든다.
 *
 * <p>품질을 재는 용도가 아니다. 실행기·보고서가 깨지지 않았는지 확인하는 용도다. formatFailureOnce 에 넣은 단계는 턴마다 첫 호출에서 형식이 틀린 출력(코드
 * 블록 안 JSON 이 잘림)을 한 번 돌려준다.
 */
final class ScriptedLlmProvider implements LlmProvider {

    static final String MODEL = "scripted-fake";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Set<AiOperation> formatFailureOnce;

    ScriptedLlmProvider() {
        this(Set.of());
    }

    ScriptedLlmProvider(Set<AiOperation> formatFailureOnce) {
        this.formatFailureOnce =
                formatFailureOnce.isEmpty()
                        ? EnumSet.noneOf(AiOperation.class)
                        : EnumSet.copyOf(formatFailureOnce);
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        JsonNode message = JSON.readTree(request.userPrompt());
        // 재시도마다 currentAttempt 가 1 씩 오르므로 첫 호출만 틀리게 한다.
        boolean firstTry = request.currentAttempt() == 1;
        String content =
                formatFailureOnce.contains(request.operation()) && firstTry
                        ? "```json\n{\"turn_id\": \"truncated"
                        : switch (request.operation()) {
                            case CAUSE_ANALYSIS -> analysis(message);
                            case RESPONSE_GENERATION -> candidate(message);
                            case RESPONSE_EVALUATION -> evaluation(message);
                            default ->
                                    throw new IllegalArgumentException(
                                            "unsupported operation " + request.operation());
                        };
        int inputTokens = (request.systemPrompt().length() + request.userPrompt().length()) / 2;
        int outputTokens = content.length() / 2;
        AiCallMetadata metadata =
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        request.operation(),
                        "scripted",
                        MODEL,
                        request.promptVersion(),
                        request.responseSchemaVersion(),
                        request.policyVersion(),
                        0,
                        request.currentAttempt(),
                        inputTokens,
                        outputTokens,
                        "stop");
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "scripted", MODEL, "stop", inputTokens, outputTokens),
                metadata);
    }

    private static String analysis(JsonNode message) {
        JsonNode state = message.get("state");
        String goal = state.get("current_micro_goal_id").asString();
        boolean lastTurn =
                state.get("turn_count").asInt() >= state.get("maximum_turn_count").asInt();
        String level = state.get("current_support_level").asString();
        return """
                {"turn_id":"%s","analysis_basis":["가짜 모델"],
                 "response_act":"ANSWER_ATTEMPT","relevance":"RELEVANT",
                 "learning_state":"PARTIAL_UNDERSTANDING",
                 "primary_gap":{"code":"MISSING_EMOTION","description":"감정을 말하지 않았다."},
                 "next_strategy":{"type":"%s","target_micro_goal_id":"%s",
                   "purpose":"다음 질문을 정한다.","recommended_support_level":"%s"},
                 "analysis_confidence":0.5}
                """
                .formatted(
                        message.get("learner_turn").get("turn_id").asString(),
                        lastTurn ? "CLOSE_ROLEPLAY" : "PROBE_EMOTION",
                        goal,
                        level);
    }

    private static String candidate(JsonNode message) {
        JsonNode analysis = message.get("analysis_result");
        JsonNode strategy = analysis.get("next_strategy");
        boolean closing = "CLOSE_ROLEPLAY".equals(strategy.get("type").asString());
        return """
                {"candidate_id":"%s","turn_id":"%s","text":"%s","response_type":"%s",
                 "strategy_used":"%s","target_micro_goal_id":"%s","support_level":"%s",
                 "previous_failed_candidate_ids":%s}
                """
                .formatted(
                        message.get("candidate_id").asString(),
                        analysis.get("turn_id").asString(),
                        closing ? "오늘 이야기해 줘서 고마워." : "그때 내 마음은 어땠을 것 같아?",
                        closing ? "CLOSING" : "GUIDING_QUESTION",
                        strategy.get("type").asString(),
                        strategy.get("target_micro_goal_id").asString(),
                        strategy.get("recommended_support_level").asString(),
                        message.get("previous_failed_candidate_ids").toString());
    }

    private static String evaluation(JsonNode message) {
        return """
                {"candidate_id":"%s","checks":{"format_valid":true},"failure_codes":[],
                 "critical_failure_count":0,"decision":"PASS","retry_target":null,
                 "safe_to_send":true,"revision_instruction":null}
                """
                .formatted(message.get("candidate_response").get("candidate_id").asString());
    }
}
