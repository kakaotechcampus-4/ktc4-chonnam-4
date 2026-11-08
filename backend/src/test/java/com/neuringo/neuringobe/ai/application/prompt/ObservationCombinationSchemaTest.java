package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ObservationCombinationSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    static Stream<Arguments> combinations() {
        var cases = new ArrayList<Arguments>();
        for (var status : GoalObservationOutput.Status.values())
            for (String verdict : List.of("SUPPORTED", "UNSUPPORTED", "INSUFFICIENT_CONTEXT"))
                for (String reason :
                        List.of(
                                "MATCHED_EXPRESSION",
                                "ABSENCE_CONFIRMED",
                                "STATUS_MISMATCH",
                                "NO_MATCHING_EXPRESSION",
                                "INSUFFICIENT_CONTEXT"))
                    for (boolean quoted : List.of(false, true))
                        cases.add(Arguments.of(status, verdict, reason, quoted));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("combinations")
    void providerSchemaMatchesExistingStatusVerdictReasonAndQuoteRules(
            GoalObservationOutput.Status status, String verdict, String reason, boolean quoted) {
        var context = context(status);
        var body = body(context);
        var row = (ObjectNode) body.path("observation_reviews").path("o0");
        row.put("verdict", verdict).put("reason_code", reason);
        if (quoted) row.put("evidence_quote", "친구가 울고 있어");
        else row.putNull("evidence_quote");
        boolean absent = status == GoalObservationOutput.Status.NOT_EXPRESSED;
        boolean allowed =
                switch (verdict) {
                    case "SUPPORTED" ->
                            absent
                                    ? reason.equals("ABSENCE_CONFIRMED")
                                    : reason.equals("MATCHED_EXPRESSION") && quoted;
                    case "UNSUPPORTED" ->
                            reason.equals("STATUS_MISMATCH") && quoted
                                    || reason.equals("NO_MATCHING_EXPRESSION") && !absent;
                    default -> reason.equals("INSUFFICIENT_CONTEXT");
                };
        var schema = new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID);
        if (allowed) schema.validate(body.toString());
        else
            assertThatThrownBy(() -> schema.validate(body.toString()))
                    .isInstanceOf(InvalidLlmOutputException.class);
    }

    @Test
    void recordedFailedCombinationIsBlockedWithoutRepairAndExistingAlternativeRemainsValid()
            throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/eval/observation-combination-failure-v1.json")) {
            var row =
                    (ObjectNode)
                            json.readTree(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var before = row.deepCopy();
            var field =
                    json.readTree(context(GoalObservationOutput.Status.EXPLICIT).evaluationSchema())
                            .path("properties")
                            .path("observation_reviews")
                            .path("properties")
                            .path("o0");
            var validator =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(field);
            assertThat(validator.validate(row)).isNotEmpty();
            assertThat(row).isEqualTo(before);
            var valid = row.deepCopy().put("reason_code", "NO_MATCHING_EXPRESSION");
            assertThat(validator.validate(valid)).isEmpty();
        }
    }

    @Test
    void sourceChecksRemainNecessaryAfterNativeSchemaAcceptance() {
        var context = context(GoalObservationOutput.Status.EXPLICIT);
        var body = body(context);
        ((ObjectNode) body.path("observation_reviews").path("o0"))
                .put("verdict", "SUPPORTED")
                .put("reason_code", "MATCHED_EXPRESSION")
                .put("evidence_quote", "없는 표현");
        new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID)
                .validate(body.toString());
        assertThatThrownBy(() -> context.validate(body))
                .isInstanceOf(InvalidLlmOutputException.class)
                .hasMessage("observation quote is not in the learner utterance");
    }

    @Test
    void legacyAnalysisSchemaStaysSeparateAndTopLevelProductionSchemaIsObject() {
        var context = context(GoalObservationOutput.Status.EXPLICIT);
        var old = json.readTree(context.schema());
        assertThat(
                        old.path("properties")
                                .path("observation_reviews")
                                .path("properties")
                                .path("o0")
                                .has("anyOf"))
                .isFalse();
        var current =
                json.readTree(
                        new EvaluationOutputSchema(context, RoleplayFixtures.CANDIDATE_ID)
                                .jsonSchema());
        assertThat(current.path("type").asString()).isEqualTo("object");
        assertThat(current.has("anyOf")).isFalse();
        assertThat(
                        current.path("properties")
                                .path("observation_reviews")
                                .path("properties")
                                .path("o0")
                                .has("anyOf"))
                .isTrue();
        assertThat(EvaluationOutputSchema.VERSION).isEqualTo("evaluation-output/v7");
    }

    private ScopedAnalysisContext context(GoalObservationOutput.Status status) {
        var input = RoleplayFixtures.input("친구가 울고 있어");
        var observations =
                com.neuringo.neuringobe.roleplay.application.RoleplayObservationTestPlans
                        .inputForScenario(input.scenario());
        var ref =
                new GoalObservationOutput.Reference(
                        RoleplayFixtures.TURN_ID,
                        RoleplayFixtures.MG_EMOTION,
                        "test-evidence:" + RoleplayFixtures.MG_EMOTION + ":0");
        return ScopedTestContexts.context(
                input,
                new GoalObservationOutput.Analysis(
                        "analysis-result/v2",
                        RoleplayFixtures.ANALYSIS_ID,
                        List.of(new GoalObservationOutput.Observation(ref, status)),
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.IN_PROGRESS, List.of())),
                observations);
    }

    private ObjectNode body(ScopedAnalysisContext context) {
        return (ObjectNode)
                json.readTree(
                        EvaluationWireFixtures.forRequest(
                                RoleplayFixtures.evaluationOutputJson("PASS", "[]", "null"),
                                context.input()));
    }
}
