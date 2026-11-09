package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 합성 P1 한 번의 실제 입력·출력만 기록한다. 운영 호출에는 연결하지 않는다. */
final class P1Diagnostics implements LlmProvider {
    private final LlmProvider delegate;
    private final List<Call> calls = new ArrayList<>();

    P1Diagnostics(LlmProvider delegate) {
        this.delegate = delegate;
    }

    static void requireAllowed(Map<String, String> options) {
        if (!options.containsKey("diagnostics")) return;
        if (!"P1".equals(options.get("cases"))
                || !"1".equals(options.getOrDefault("repeats", "1"))
                || !java.util.Set.of(
                                RegressionSet.DEFAULT_RESOURCE, RegressionSet.CHARACTER_P1_RESOURCE)
                        .contains(options.getOrDefault("set", RegressionSet.DEFAULT_RESOURCE))
                || options.containsKey("suites")
                || options.containsKey("speech")
                || options.containsKey("skip-llm")
                || options.containsKey("compare")) {
            throw new IllegalArgumentException(
                    "진단은 승인된 합성 P1 세트의 --cases P1 --repeats 1에서만 사용할 수 있습니다.");
        }
    }

    @Override
    public AiCallResult<LlmCompletion> complete(LlmRequest request) {
        long start = System.nanoTime();
        var result = delegate.complete(request);
        String output = null;
        String failure = null;
        if (result instanceof AiCallResult.Success<LlmCompletion> success)
            output = success.data().content();
        else if (result instanceof AiCallResult.Failure<?> failed)
            failure = failed.failure().type().name();
        var input = EvalReport.JSON.readTree(request.userPrompt());
        calls.add(
                new Call(
                        calls.size() + 1,
                        request.operation().name(),
                        request.currentAttempt(),
                        request.traceContext().analysisId(),
                        request.traceContext().candidateId(),
                        request.promptVersion(),
                        request.responseSchemaVersion(),
                        request.responseJsonSchema() != null,
                        input.path("scenario").has("ai_character") ? "CHARACTER" : "NARRATOR",
                        input,
                        output,
                        failure,
                        (System.nanoTime() - start) / 1_000_000));
        return result;
    }

    void write(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.write(
                    dir.resolve("diagnostics.jsonl"),
                    calls.stream().map(EvalReport.JSON::writeValueAsString).toList(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record Call(
            int sequence,
            String operation,
            int attempt,
            UUID analysisId,
            UUID candidateId,
            String promptVersion,
            String schemaVersion,
            boolean nativeSchema,
            String roleType,
            JsonNode input,
            String output,
            String providerFailure,
            long wallMs) {}
}
