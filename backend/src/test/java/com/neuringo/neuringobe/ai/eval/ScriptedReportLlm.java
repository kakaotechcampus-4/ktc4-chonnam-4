package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 키 없이 reportEval 실행기와 보고서가 깨지지 않았는지 보는 가짜 모델. 첫 번째 비기술 근거 하나로 초안을 쓰고 평가는 늘 PASS 다. 품질을 재는 용도가 아니다.
 */
final class ScriptedReportLlm implements LlmProvider {

    static final String MODEL = "scripted-report-fake";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        String content = request.operation() == AiOperation.REPORT_REVIEW ? pass() : draft(request);
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
                        content.length(),
                        content.length() / 2,
                        "stop");
        return new AiCallResult.Success<>(
                new LlmCompletion(content, "scripted", MODEL, "stop", null, null), metadata);
    }

    private static String draft(LlmRequest request) {
        String evidenceId = "E1";
        for (JsonNode item : JSON.readTree(request.userPrompt()).get("evidence")) {
            if (!"TECHNICAL".equals(item.get("kind").asString())) {
                evidenceId = item.get("id").asString();
                break;
            }
        }
        return """
                {"summary":"관찰한 행동을 정리했어요.",
                 "observations":[{"text":"관찰한 행동이 있어요","evidence_ids":["%s"]}],
                 "effective_supports":[],"difficult_points":[]}
                """
                .formatted(evidenceId);
    }

    private static String pass() {
        return """
                {"decision":"PASS",
                 "scores":{"grounded":4,"non_diagnostic":4,"technical_separation":4,"actionable":4,"readable":4},
                 "failure_codes":[],"revision_instruction":null}
                """;
    }
}
