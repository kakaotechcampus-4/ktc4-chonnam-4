package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 발화자 신원·실제 후보 인용·실패 코드 정합성을 별도로 검사한다. */
public final class CandidateRoleValidation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> CODE_CHECKS =
            Map.ofEntries(
                    Map.entry("UNSAFE_EXPRESSION", "safety_valid"),
                    Map.entry("IMMEDIATE_RISK_IN_INPUT", "safety_valid"),
                    Map.entry("ASKS_PERSONAL_INFO", "privacy_valid"),
                    Map.entry("INTERNAL_INSTRUCTION_LEAK", "privacy_valid"),
                    Map.entry("SCENARIO_CONTRADICTION", "scenario_consistent"),
                    Map.entry("REINFORCES_INCORRECT_ANSWER", "educationally_correct"),
                    Map.entry("ANALYSIS_RESPONSE_MISMATCH", "analysis_response_consistent"),
                    Map.entry("GOAL_SKIPPED", "goal_aligned"),
                    Map.entry("SUPPORT_LEVEL_MISMATCH", "difficulty_appropriate"),
                    Map.entry("TOO_DIFFICULT", "difficulty_appropriate"),
                    Map.entry("CHARACTER_BREAK", "conversation_coherent"),
                    Map.entry("REPEATED_QUESTION", "non_repetitive"),
                    Map.entry("NOT_ANSWERABLE", "answerable"),
                    Map.entry("EMPTY_OR_TRUNCATED", "format_valid"),
                    Map.entry("MISSING_QUESTION", "format_valid"),
                    Map.entry("MULTIPLE_QUESTIONS", "format_valid"),
                    Map.entry("TOO_LONG", "format_valid"));
    private final SplitReviewValidation.CandidateReview base;
    private final ObjectNode input;
    private final String schema;
    private final com.networknt.schema.Schema validator;

    public CandidateRoleValidation(SplitReviewValidation.CandidateReview base) {
        this.base = base;
        input = (ObjectNode) base.input();
        var character = input.path("scenario").path("ai_character");
        for (String key : List.of("id", "role", "speech_style"))
            if (!character.path(key).isString() || character.path(key).asString().isBlank())
                throw new IllegalArgumentException("승인된 캐릭터 신원이 필요합니다.");
        var binding = input.putObject("speaker_context");
        binding.putObject("learner_turn").put("speaker_kind", "LEARNER");
        var actor = binding.putObject("candidate_text");
        actor.put("speaker_kind", "AI_CHARACTER");
        actor.set("character_id", character.path("id"));
        actor.set("role", character.path("role"));
        actor.set("speech_style", character.path("speech_style"));
        if (!input.path("candidate_response").path("mascot_hint").isMissingNode()
                && !input.path("candidate_response").path("mascot_hint").isNull())
            throw new IllegalArgumentException("이번 고정 진단은 마스코트 없는 후보만 허용합니다.");
        binding.putObject("mascot_hint").put("available", false).put("speaker_kind", "MASCOT");
        var root = (ObjectNode) JSON.readTree(base.schema());
        var role = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        var properties = role.putObject("properties");
        properties.set(
                "reviewed_character_id",
                JSON.valueToTree(
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of(character.path("id").asString()))));
        properties.set(
                "verdict",
                JSON.valueToTree(
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of(
                                        "IN_CHARACTER",
                                        "SELF_AS_THIRD_PERSON_NARRATION",
                                        "INSUFFICIENT_CONTEXT"))));
        properties.set(
                "evidence_quote", JSON.valueToTree(Map.of("type", List.of("string", "null"))));
        properties.set("explanation", JSON.valueToTree(Map.of("type", "string")));
        var required = role.putArray("required");
        properties.propertyNames().forEach(required::add);
        ((ObjectNode) root.path("properties")).set("role_review", role);
        root.withArray("required").add("role_review");
        schema = root.toString();
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(root);
    }

    public JsonNode input() {
        return input.deepCopy();
    }

    public String schema() {
        return schema;
    }

    public static String codeCheckMismatch(JsonNode output) {
        for (var code : output.path("failure_codes")) {
            String check = CODE_CHECKS.get(code.asString());
            if (check != null
                    && (!output.path("checks").path(check).isBoolean()
                            || output.path("checks").path(check).asBoolean()))
                return code.asString() + ":" + check;
        }
        return null;
    }

    public Checks validate(String raw) {
        JsonNode output;
        try {
            output = JSON.readTree(raw);
            if (output == null || !validator.validate(output).isEmpty())
                return failed(
                        "SCHEMA_FAILURE",
                        false,
                        null,
                        null,
                        "candidate role review does not match schema");
        } catch (RuntimeException failure) {
            return failed(
                    "SCHEMA_FAILURE",
                    false,
                    null,
                    null,
                    "candidate role review does not match schema");
        }
        var review = output.path("role_review");
        String verdict = review.path("verdict").asString();
        String mismatch = codeCheckMismatch(output);
        if (mismatch != null)
            return failed(
                    "CANDIDATE_CODE_CHECK_MISMATCH",
                    true,
                    verdict,
                    mismatch,
                    "failure code contradicts evaluation check");
        String explanation = review.path("explanation").asString();
        if (explanation.isBlank()
                || explanation.codePointCount(0, explanation.length()) > 200
                || explanation
                        .codePoints()
                        .noneMatch(
                                c ->
                                        Character.UnicodeScript.of(c)
                                                == Character.UnicodeScript.HANGUL))
            return failed(
                    "INVALID_ROLE_EVIDENCE", true, verdict, null, "role explanation is invalid");
        var quote = review.path("evidence_quote");
        boolean unknown = verdict.equals("INSUFFICIENT_CONTEXT");
        if ((!unknown && !quote.isString())
                || (quote.isString()
                        && (quote.asString().isBlank()
                                || !input.path("candidate_response")
                                        .path("text")
                                        .asString()
                                        .contains(quote.asString()))))
            return failed(
                    "INVALID_ROLE_EVIDENCE",
                    true,
                    verdict,
                    null,
                    "role quote is not from current candidate text");
        boolean priority =
                Set.of("SAFETY_ESCALATION", "CONFIRM_INPUT")
                        .contains(output.path("decision").asString());
        if (unknown && !priority)
            return failed(
                    "INDETERMINATE_OUTPUT",
                    true,
                    verdict,
                    null,
                    "role review has insufficient context");
        if (!priority && verdict.equals("SELF_AS_THIRD_PERSON_NARRATION")) {
            boolean codePresent = false;
            for (var code : output.path("failure_codes"))
                codePresent |= code.asString().equals("CHARACTER_BREAK");
            if (!codePresent || output.path("checks").path("conversation_coherent").asBoolean())
                return failed(
                        "ROLE_REVIEW_CONTRADICTS_CHECKS",
                        true,
                        verdict,
                        null,
                        "third-person self narration requires character break");
        }
        try {
            // 새 역할 검수를 먼저 검사한 뒤 기존 후보 계약에 해당하는 필드만 원본 값 그대로 전달한다.
            var projected = (ObjectNode) output.deepCopy();
            projected.remove("role_review");
            var result = base.validate(projected.toString());
            return new Checks("VALID_CANDIDATE_REVIEW", true, verdict, null, result, null);
        } catch (InvalidLlmOutputException | IllegalArgumentException failure) {
            return failed(
                    "INVALID_CANDIDATE_REVIEW",
                    true,
                    verdict,
                    null,
                    "candidate decision contradicts validation rules");
        }
    }

    private static Checks failed(
            String outcome, boolean schemaValid, String verdict, String mismatch, String reason) {
        return new Checks(outcome, schemaValid, verdict, mismatch, null, reason);
    }

    public record Checks(
            String outcome,
            boolean schemaValid,
            String roleVerdict,
            String codeCheckMismatch,
            EvaluationResult combinedResult,
            String reason) {}
}
