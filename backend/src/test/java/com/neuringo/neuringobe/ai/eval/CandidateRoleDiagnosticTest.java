package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;
import com.neuringo.neuringobe.ai.application.prompt.CandidateRoleValidation;
import com.neuringo.neuringobe.ai.application.prompt.SplitReviewValidation;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CandidateRoleDiagnosticTest {
    private final CandidateRoleDiagnostic diagnostic = new CandidateRoleDiagnostic();

    @Test
    void preservesApprovedContextAndBindsActualSpeakersWithoutSendingGoldLabels() {
        var a = diagnostic.samples().getFirst().validation().input();
        var c = diagnostic.samples().getLast().validation().input();
        for (String key : a.propertyNames()) {
            if (!key.equals("candidate_response")) assertThat(c.path(key)).isEqualTo(a.path(key));
        }
        var source =
                EvalReport.JSON
                        .readTree(
                                CandidateRoleDiagnostic.loadBaseline()
                                        .path("source_fixture_json")
                                        .asString())
                        .path("input");
        assertThat(a.path("candidate_response")).isEqualTo(source.path("candidate_response"));
        var character = a.path("scenario").path("ai_character");
        assertThat(a.path("speaker_context").path("candidate_text").path("character_id"))
                .isEqualTo(character.path("id"));
        assertThat(a.path("speaker_context").path("candidate_text").path("role"))
                .isEqualTo(character.path("role"));
        assertThat(a.path("speaker_context").path("mascot_hint").path("available").asBoolean())
                .isFalse();
        assertThat(a.path("speaker_context").path("learner_turn").path("speaker_kind").asString())
                .isEqualTo("LEARNER");
        assertThat(c.path("candidate_response").path("support_level").asString()).isEqualTo("S0");
        assertThat(c.path("candidate_response").path("candidate_id"))
                .isNotEqualTo(a.path("candidate_response").path("candidate_id"));
        for (var s : diagnostic.samples())
            assertThat(s.request().userPrompt())
                    .doesNotContain("expected_role", "case_id", "expected_verdict");
        ((ObjectNode) a.path("speaker_context").path("candidate_text")).put("character_id", "위조");
        assertThat(
                        diagnostic
                                .samples()
                                .getFirst()
                                .validation()
                                .input()
                                .path("speaker_context")
                                .path("candidate_text")
                                .path("character_id"))
                .isEqualTo(character.path("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "request", "schema", "prompt", "raw", "fixture", "approval"})
    void refusesAlteredOrUnapprovedCachedAnalysis(String mode) {
        var baseline = (ObjectNode) CandidateRoleDiagnostic.loadBaseline();
        if (mode.equals("hash"))
            ((ObjectNode) baseline.path("file_sha256")).put("analysis_request_json", "불일치");
        else {
            String key =
                    switch (mode) {
                        case "request" -> "analysis_request_json";
                        case "schema" -> "analysis_schema_json";
                        case "prompt" -> "analysis_system_prompt";
                        case "raw", "approval" -> "analysis_result_json";
                        default -> "source_fixture_json";
                    };
            if (mode.equals("approval")) {
                var entry = (ObjectNode) EvalReport.JSON.readTree(baseline.path(key).asString());
                var raw =
                        (ObjectNode) EvalReport.JSON.readTree(entry.path("raw_output").asString());
                ((ObjectNode) raw.path("current_goal_review"))
                        .put("verdict", "UNSUPPORTED")
                        .put("reason_code", "LEARNING_STATE_MISMATCH");
                entry.put("raw_output", raw.toString());
                baseline.put(key, entry.toString());
            } else baseline.put(key, baseline.path(key).asString() + "변조");
            ((ObjectNode) baseline.path("file_sha256"))
                    .put(key, IntegratedEvaluationDiagnostic.hash(baseline.path(key).asString()));
        }
        assertThatThrownBy(() -> new CandidateRoleDiagnostic(baseline))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "UNSAFE_EXPRESSION,safety_valid",
        "IMMEDIATE_RISK_IN_INPUT,safety_valid",
        "ASKS_PERSONAL_INFO,privacy_valid",
        "INTERNAL_INSTRUCTION_LEAK,privacy_valid",
        "SCENARIO_CONTRADICTION,scenario_consistent",
        "REINFORCES_INCORRECT_ANSWER,educationally_correct",
        "ANALYSIS_RESPONSE_MISMATCH,analysis_response_consistent",
        "GOAL_SKIPPED,goal_aligned",
        "SUPPORT_LEVEL_MISMATCH,difficulty_appropriate",
        "TOO_DIFFICULT,difficulty_appropriate",
        "CHARACTER_BREAK,conversation_coherent",
        "REPEATED_QUESTION,non_repetitive",
        "NOT_ANSWERABLE,answerable",
        "EMPTY_OR_TRUNCATED,format_valid",
        "MISSING_QUESTION,format_valid",
        "MULTIPLE_QUESTIONS,format_valid",
        "TOO_LONG,format_valid"
    })
    void checksEachCodeImplicationWithoutChangingModelValues(String code, String check) {
        var body = output(diagnostic.samples().getFirst().request());
        body.putArray("failure_codes").add(code);
        var before = body.deepCopy();
        assertThat(CandidateRoleValidation.codeCheckMismatch(body)).isEqualTo(code + ":" + check);
        assertThat(body).isEqualTo(before);
        ((ObjectNode) body.path("checks")).put(check, false);
        assertThat(CandidateRoleValidation.codeCheckMismatch(body)).isNull();
        body.putArray("failure_codes");
        assertThat(CandidateRoleValidation.codeCheckMismatch(body)).isNull();
    }

    @Test
    void detectsThePreviousActualContradictionWithoutRewritingTheRawOutput() {
        var old =
                EvalReport.JSON
                        .readTree(
                                CandidateRoleDiagnostic.loadBaseline()
                                        .path("candidate_result_json")
                                        .asString())
                        .path("raw_output")
                        .asString();
        var node = EvalReport.JSON.readTree(old);
        var copy = node.deepCopy();
        assertThat(CandidateRoleValidation.codeCheckMismatch(node))
                .isEqualTo("REINFORCES_INCORRECT_ANSWER:educationally_correct");
        assertThat(node).isEqualTo(copy);
        assertThat(diagnostic.samples().getFirst().validation().validate(old).outcome())
                .isEqualTo("SCHEMA_FAILURE");
    }

    @Test
    void runsOnlyTwoCandidatesAndKeepsRoleResultSeparateFromSupportResult() {
        var requests = new ArrayList<LlmRequest>();
        var saved = new ArrayList<CandidateRoleDiagnostic.Entry>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            var body = output(request);
                            if (requests.size() == 1) {
                                body.put("decision", "REGENERATE");
                                body.putArray("failure_codes").add("SUPPORT_LEVEL_MISMATCH");
                                ((ObjectNode) body.path("checks"))
                                        .put("difficulty_appropriate", false);
                                body.set("revision_instruction", revision());
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        saved::add);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.bothServerAccepted()).isTrue();
        assertThat(report.bothRoleLabelsMatch()).isTrue();
        assertThat(saved).containsExactlyElementsOf(report.cases());
        assertThat(requests)
                .hasSize(2)
                .allSatisfy(
                        r -> {
                            assertThat(r.promptVersion())
                                    .isEqualTo(CandidateRoleDiagnostic.VERSION);
                            assertThat(r.currentAttempt()).isEqualTo(1);
                        });
        assertThat(report.cases())
                .allSatisfy(e -> assertThat(e.checks().combinedResult().canDeliver()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"quote", "quote-null", "explanation", "role-code", "unknown", "code-check"})
    void continuesOnlyThePlannedSecondCandidateAfterSemanticFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            var body = output(request);
                            if (calls.getAndIncrement() == 0) {
                                var role = (ObjectNode) body.path("role_review");
                                switch (mode) {
                                    case "quote" -> role.put("evidence_quote", "너 발표하다가 말을 못 찾아서");
                                    case "quote-null" -> role.putNull("evidence_quote");
                                    case "explanation" -> role.put("explanation", "English only");
                                    case "role-code" ->
                                            role.put("verdict", "SELF_AS_THIRD_PERSON_NARRATION");
                                    case "unknown" ->
                                            role.put("verdict", "INSUFFICIENT_CONTEXT")
                                                    .putNull("evidence_quote");
                                    case "code-check" ->
                                            body.putArray("failure_codes")
                                                    .add("REINFORCES_INCORRECT_ANSWER");
                                    default -> throw new AssertionError();
                                }
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(2);
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.stoppedReason()).isNull();
        assertThat(report.bothServerAccepted()).isFalse();
        assertThat(report.cases().getFirst().checks().combinedResult()).isNull();
        assertThat(report.cases().getFirst().outcome())
                .isEqualTo(
                        switch (mode) {
                            case "role-code" -> "ROLE_REVIEW_CONTRADICTS_CHECKS";
                            case "unknown" -> "INDETERMINATE_OUTPUT";
                            case "code-check" -> "CANDIDATE_CODE_CHECK_MISMATCH";
                            default -> "INVALID_ROLE_EVIDENCE";
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "character-id",
                "candidate-id",
                "analysis-id",
                "missing-role",
                "extra-role",
                "provider",
                "execution"
            })
    void stopsOnSchemaTransportOrExecutionFailure(String mode) {
        var calls = new AtomicInteger();
        var report =
                diagnostic.run(
                        request -> {
                            calls.incrementAndGet();
                            if (mode.equals("execution")) throw new IllegalStateException();
                            if (mode.equals("provider"))
                                return new AiCallResult.Failure<>(
                                        new AiFailure(AiFailureType.TIMEOUT, null),
                                        metadata(request));
                            var body = output(request);
                            switch (mode) {
                                case "character-id" ->
                                        ((ObjectNode) body.path("role_review"))
                                                .put("reviewed_character_id", "OTHER");
                                case "candidate-id" ->
                                        body.put(
                                                "candidate_id",
                                                java.util.UUID.randomUUID().toString());
                                case "analysis-id" ->
                                        body.put(
                                                "analysis_id",
                                                java.util.UUID.randomUUID().toString());
                                case "missing-role" -> body.remove("role_review");
                                case "extra-role" ->
                                        ((ObjectNode) body.path("role_review"))
                                                .put("mascot_quote", "없는 발화");
                                default -> throw new AssertionError();
                            }
                            return success(request, body.toString());
                        },
                        RoleplayTurnDeadline.start(),
                        ignored -> {});
        assertThat(calls).hasValue(1);
        assertThat(report.stoppedReason())
                .isEqualTo(
                        mode.equals("provider")
                                ? "PROVIDER_FAILURE"
                                : mode.equals("execution")
                                        ? "EXECUTION_FAILURE"
                                        : "SCHEMA_FAILURE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFETY_ESCALATION", "CONFIRM_INPUT", "SAFETY_REGENERATE"})
    void preservesPriorityAndSafeRecovery(String decision) {
        var body = output(diagnostic.samples().getFirst().request());
        body.put("decision", decision);
        String code =
                decision.equals("SAFETY_ESCALATION")
                        ? "IMMEDIATE_RISK_IN_INPUT"
                        : decision.equals("CONFIRM_INPUT")
                                ? "INPUT_MEANING_UNCLEAR"
                                : "UNSAFE_EXPRESSION";
        body.putArray("failure_codes").add(code);
        ((ObjectNode) body.path("checks")).put("safety_valid", false);
        if (decision.equals("SAFETY_REGENERATE")) body.set("revision_instruction", revision());
        else
            ((ObjectNode) body.path("role_review"))
                    .put("verdict", "INSUFFICIENT_CONTEXT")
                    .putNull("evidence_quote");
        var checks = diagnostic.samples().getFirst().validation().validate(body.toString());
        assertThat(checks.outcome()).isEqualTo("VALID_CANDIDATE_REVIEW");
        assertThat(checks.combinedResult().canDeliver()).isFalse();
        assertThat(checks.combinedResult().decision().name()).isEqualTo(decision);
    }

    @Test
    void rejectsLateResultsAndUsesOneCancellableBudget() {
        var deadline = RoleplayTurnDeadline.start();
        var requests = new ArrayList<LlmRequest>();
        var report =
                diagnostic.run(
                        request -> {
                            requests.add(request);
                            request.callBudget().limit(Duration.ofSeconds(60));
                            if (requests.size() == 2) deadline.cancel();
                            return success(request, output(request).toString());
                        },
                        deadline,
                        ignored -> {});
        assertThat(report.physicalCalls()).isEqualTo(2);
        assertThat(report.stoppedReason()).isEqualTo("TIMED_OUT");
        assertThat(report.cases().getLast().checks()).isNull();
        assertThat(report.cases().getLast().rawOutput()).isNotNull();
        assertThat(requests)
                .allSatisfy(
                        r ->
                                assertThatThrownBy(
                                                () -> r.callBudget().limit(Duration.ofSeconds(60)))
                                        .isInstanceOf(RoleplayTurnDeadline.Expired.class));
        assertThat(
                        diagnostic
                                .run(
                                        r -> {
                                            throw new AssertionError("추가 호출 금지");
                                        },
                                        deadline,
                                        ignored -> {})
                                .physicalCalls())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"character", "mascot"})
    void refusesMissingCharacterOrUnplannedMascot(String mode) {
        var baseline = CandidateRoleDiagnostic.loadBaseline();
        var original =
                (ObjectNode)
                        EvalReport.JSON
                                .readTree(baseline.path("source_fixture_json").asString())
                                .path("input");
        if (mode.equals("character"))
            ((ObjectNode) original.path("scenario").path("ai_character")).remove("id");
        var candidate = (ObjectNode) original.path("candidate_response");
        if (mode.equals("mascot")) candidate.put("mascot_hint", "이번 진단에 없는 마스코트");
        String raw =
                EvalReport.JSON
                        .readTree(baseline.path("analysis_result_json").asString())
                        .path("raw_output")
                        .asString();
        assertThatThrownBy(
                        () ->
                                new CandidateRoleValidation(
                                        new SplitReviewValidation(original)
                                                .candidateReview(raw, candidate)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void comparesSchemaConstraintsWithoutDependingOnEnumOrRequiredOrder() {
        var sample = diagnostic.samples().getFirst();
        var original = EvalReport.JSON.readTree(sample.request().responseJsonSchema());
        var reordered = (ObjectNode) original.deepCopy();
        var values = new ArrayList<JsonNode>();
        reordered.path("required").forEach(values::add);
        java.util.Collections.reverse(values);
        var required = reordered.putArray("required");
        values.forEach(required::add);
        assertThat(CandidateRoleDiagnostic.normalizedSchema(reordered))
                .isEqualTo(CandidateRoleDiagnostic.normalizedSchema(original));
        ((ObjectNode) reordered.path("properties").path("candidate_id")).put("type", "number");
        assertThat(CandidateRoleDiagnostic.normalizedSchema(reordered))
                .isNotEqualTo(CandidateRoleDiagnostic.normalizedSchema(original));
    }

    private static ObjectNode output(LlmRequest request) {
        var input = EvalReport.JSON.readTree(request.userPrompt());
        boolean negative =
                input.path("candidate_response").path("text").asString().startsWith("친구가");
        var body = EvalReport.JSON.createObjectNode();
        body.set("analysis_id", input.path("analysis_id"));
        body.set("candidate_id", input.path("candidate_response").path("candidate_id"));
        var checks = body.putObject("checks");
        for (String key :
                List.of(
                        "format_valid",
                        "safety_valid",
                        "privacy_valid",
                        "scenario_consistent",
                        "analysis_response_consistent",
                        "goal_aligned",
                        "educationally_correct",
                        "difficulty_appropriate",
                        "conversation_coherent",
                        "answerable",
                        "non_repetitive")) checks.put(key, true);
        body.put("decision", negative ? "REGENERATE" : "PASS");
        body.putArray("failure_codes");
        if (negative) {
            checks.put("conversation_coherent", false);
            body.withArray("failure_codes").add("CHARACTER_BREAK");
            body.set("revision_instruction", revision());
        } else body.putNull("revision_instruction");
        var role = body.putObject("role_review");
        role.set(
                "reviewed_character_id",
                input.path("speaker_context").path("candidate_text").path("character_id"));
        role.put("verdict", negative ? "SELF_AS_THIRD_PERSON_NARRATION" : "IN_CHARACTER");
        role.set("evidence_quote", input.path("candidate_response").path("text"));
        role.put("explanation", negative ? "자기 캐릭터를 밖에서 제삼자로 서술한다." : "자기 캐릭터가 직접 자기 상태를 말한다.");
        return body;
    }

    private static ObjectNode revision() {
        var node = EvalReport.JSON.createObjectNode();
        for (String key : List.of("keep", "change", "avoid", "required"))
            node.putArray(key).add("실제 후보 문제를 수정한다.");
        return node;
    }

    private static AiCallMetadata metadata(LlmRequest request) {
        return new AiCallMetadata(
                request.traceContext().requestId(),
                request.operation(),
                "scripted",
                "scripted",
                request.promptVersion(),
                request.responseSchemaVersion(),
                null,
                0,
                1,
                10,
                10,
                "stop");
    }

    private static AiCallResult<LlmCompletion> success(LlmRequest request, String raw) {
        return new AiCallResult.Success<>(
                new LlmCompletion(raw, "scripted", "scripted", "stop", 10, 10), metadata(request));
    }
}
