package com.neuringo.neuringobe.ai.infrastructure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.AiDiagnostics;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.structured.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class FileAiDiagnosticsTest {
    @TempDir Path tmp;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void keepsMalformedOutputAndValidationInOrderWithoutLeakingToConsole(CapturedOutput console)
            throws Exception {
        var sink = sink();
        var id = UUID.randomUUID();
        sink.begin(id);
        var request = request(id);
        LlmProvider backend = r -> success(r, "{\"decision\":\"PASS\"");
        var executor =
                new StructuredLlmExecutor(new DiagnosticLlmProvider(backend, sink), true, sink);
        var result =
                executor.execute(
                        request,
                        raw -> {
                            throw new InvalidLlmOutputException(
                                    "required JSON field missing", null);
                        });
        sink.end(id, AiDiagnostics.fields("result", "RETRY_REQUIRED"));
        var events = Files.readAllLines(file(id)).stream().map(json::readTree).toList();
        assertThat(events)
                .extracting(e -> e.path("event").asString())
                .containsExactly("MODEL_REQUEST", "MODEL_RESPONSE", "VALIDATION", "HTTP_RESULT");
        assertThat(events.get(1).path("data").path("output").asString())
                .isEqualTo("{\"decision\":\"PASS\"");
        assertThat(events.get(2).path("data").path("outcome").asString())
                .isEqualTo("INVALID_OUTPUT_FORMAT");
        assertThat(events.get(2).path("data").path("reason").asString())
                .isEqualTo("required JSON field missing");
        assertThat(events.getFirst().path("data").path("system_prompt").asString())
                .isEqualTo("private-system-prompt");
        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        assertThat(console)
                .doesNotContain(
                        "private-system-prompt", "private-model-input", "{\"decision\":\"PASS\"");
        assertThat(Files.readString(file(id))).doesNotContain("Authorization", "apiKey", "baseUrl");
        assertThat(Files.getPosixFilePermissions(file(id)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(file(id).getParent()))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(sink.active(id)).isFalse();
    }

    @Test
    void boundsAdmissionAndCaptureWindow() throws Exception {
        var time = new AtomicLong();
        var sink =
                new FileAiDiagnostics(
                        tmp.resolve("capture"), Duration.ofSeconds(2), 1, 100000, 10000, time::get);
        var id = UUID.randomUUID();
        sink.begin(id);
        var other = UUID.randomUUID();
        sink.begin(other);
        assertThat(sink.active(id)).isTrue();
        assertThat(sink.active(other)).isFalse();
        sink.record(id, "TEST", AiDiagnostics.fields("value", "before"));
        time.set(Duration.ofSeconds(3).toNanos());
        sink.record(id, "TEST", AiDiagnostics.fields("value", "after"));
        assertThat(sink.active(id)).isFalse();
        assertThat(
                        Files.readString(
                                tmp.resolve("capture")
                                        .resolve(id.toString())
                                        .resolve("diagnostics.jsonl")))
                .contains("before")
                .doesNotContain("after");
    }

    @Test
    void marksOversizeEventsAndCountsExistingFilesInTotalBudget() throws Exception {
        var root = tmp.resolve("capture");
        var sink = new FileAiDiagnostics(root, Duration.ofMinutes(1), 2, 1000, 500);
        var id = UUID.randomUUID();
        sink.begin(id);
        sink.record(id, "MODEL_RESPONSE", AiDiagnostics.fields("output", "x".repeat(1000)));
        Path file = root.resolve(id.toString()).resolve("diagnostics.jsonl");
        assertThat(Files.readString(file))
                .contains("CAPTURE_SKIPPED", "EVENT_LIMIT")
                .doesNotContain("x".repeat(100));
        var full = new FileAiDiagnostics(root, Duration.ofMinutes(1), 1, 1, 500);
        var second = UUID.randomUUID();
        full.begin(second);
        assertThat(full.active(second)).isFalse();
        assertThat(Files.exists(root.resolve(second.toString()))).isFalse();
    }

    @Test
    void storageFailureDoesNotChangeTheProviderResultAndLateWorkersDoNotAppend() throws Exception {
        var sink = sink();
        var id = UUID.randomUUID();
        sink.begin(id);
        Files.delete(file(id));
        Files.createDirectory(file(id));
        var expected = success(request(id), "normal output");
        var provider = new DiagnosticLlmProvider(r -> expected, sink);
        assertThat(provider.complete(request(id))).isSameAs(expected);
        assertThat(sink.active(id)).isFalse();
        var other = UUID.randomUUID();
        sink.begin(other);
        sink.end(other, AiDiagnostics.fields("result", "TIMEOUT"));
        String before = Files.readString(file(other));
        sink.record(other, "MODEL_RESPONSE", AiDiagnostics.fields("output", "late"));
        assertThat(Files.readString(file(other))).isEqualTo(before);
    }

    @Test
    void isolatesConcurrentRequestsAndKeepsJsonLinesWhole() throws Exception {
        var sink = sink();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        sink.begin(a);
        sink.begin(b);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks =
                    executor.invokeAll(
                            List.of(
                                    () -> {
                                        for (int i = 0; i < 25; i++)
                                            sink.record(
                                                    a,
                                                    "TEST",
                                                    AiDiagnostics.fields("value", "only-a"));
                                        return true;
                                    },
                                    () -> {
                                        for (int i = 0; i < 25; i++)
                                            sink.record(
                                                    b,
                                                    "TEST",
                                                    AiDiagnostics.fields("value", "only-b"));
                                        return true;
                                    }));
            for (var task : tasks) task.get();
        }
        assertThat(Files.readAllLines(file(a)))
                .hasSize(25)
                .allSatisfy(
                        line ->
                                assertThat(json.readTree(line).path("request_id").asString())
                                        .isEqualTo(a.toString()));
        assertThat(Files.readString(file(a))).doesNotContain("only-b");
        assertThat(Files.readAllLines(file(b))).hasSize(25);
    }

    @Test
    void ignoresUnadmittedRequestsAndRejectsSymlinkRoots() throws Exception {
        var sink = sink();
        var id = UUID.randomUUID();
        sink.record(id, "TEST", AiDiagnostics.fields("value", "not admitted"));
        assertThat(Files.exists(file(id))).isFalse();
        Path link = tmp.resolve("link");
        Files.createSymbolicLink(link, tmp.resolve("capture"));
        var denied = new FileAiDiagnostics(link, Duration.ofMinutes(1), 1, 1000, 500);
        denied.begin(id);
        assertThat(denied.active(id)).isFalse();
    }

    private FileAiDiagnostics sink() {
        return new FileAiDiagnostics(
                tmp.resolve("capture"), Duration.ofMinutes(1), 10, 100000, 10000);
    }

    private Path file(UUID id) {
        return tmp.resolve("capture").resolve(id.toString()).resolve("diagnostics.jsonl");
    }

    private LlmRequest request(UUID id) {
        var trace = new AiTraceContext(id, id, null, null, null, null, id, id, id, id);
        return new LlmRequest(
                trace,
                new AiAttemptContext(1, 1, 1),
                AiOperation.RESPONSE_EVALUATION,
                1,
                "private-system-prompt",
                "private-model-input",
                "prompt-v1",
                "schema-v1",
                null);
    }

    private AiCallResult<LlmCompletion> success(LlmRequest r, String raw) {
        var meta =
                new AiCallMetadata(
                        r.traceContext().requestId(),
                        r.operation(),
                        "test",
                        "test-model",
                        "prompt-v1",
                        "schema-v1",
                        null,
                        1,
                        1,
                        10,
                        10,
                        "STOP");
        return new AiCallResult.Success<>(
                new LlmCompletion(raw, "test", "test-model", "STOP", 10, 10), meta);
    }
}
