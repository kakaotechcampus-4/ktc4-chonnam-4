package com.neuringo.neuringobe.ai.application.prompt;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 요청의 바인딩과 같은 스키마를 제공자 제약과 수신 검증에 사용한다. */
final class EvaluationOutputSchema {
    static final String VERSION = "evaluation-output/v9";
    static final String REQUIRED_CONFLICT = "캐릭터 재생성의 필수 조건은 서버가 구성합니다.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String jsonSchema;
    private final Schema validator;
    private final Schema directnessValidator;
    private final CharacterResponseContract contract;
    private final CandidateResponse candidate;
    private final AnalysisResult analysis;
    private final Schema progressValidator;
    private final Schema cueValidator;

    EvaluationOutputSchema(ScopedAnalysisContext context, UUID candidateId) {
        this(context, candidateId, null, null);
    }

    EvaluationOutputSchema(
            ScopedAnalysisContext context,
            CandidateResponse candidate,
            CharacterResponseContract contract) {
        this(context, candidate.candidateId(), candidate, contract);
    }

    private EvaluationOutputSchema(
            ScopedAnalysisContext context,
            UUID candidateId,
            CandidateResponse candidate,
            CharacterResponseContract contract) {
        this.contract = contract;
        this.analysis = context.reviewedAnalysis();
        this.candidate = candidate;
        var schema = (ObjectNode) JSON.readTree(context.evaluationSchema());
        var properties = (ObjectNode) schema.path("properties");
        for (String key :
                List.of(
                        "observation_reviews",
                        "current_goal_review",
                        "activity_review",
                        "interpretation_strategy_review")) {
            ((ObjectNode) properties.path(key))
                    .set("type", JSON.valueToTree(List.of("object", "null")));
        }
        properties.set(
                "candidate_id",
                JSON.valueToTree(
                        java.util.Map.of(
                                "type", "string", "enum", List.of(candidateId.toString()))));
        var parts = EvaluationSchemaParts.properties().deepCopy();
        for (String key : List.of("checks", "decision", "failure_codes", "revision_instruction"))
            properties.set(key, parts.path(key));
        if (contract != null) {
            ((ObjectNode) properties.path("failure_codes").path("items"))
                    .set("enum", JSON.valueToTree(allowedFailureCodes()));
            var revision = (ObjectNode) properties.path("revision_instruction");
            ((ObjectNode) revision.path("properties")).remove("required");
            revision.set("required", JSON.valueToTree(List.of("keep", "change", "avoid")));
        }
        var cue = JSON.valueToTree(TargetCueViolation.schema(analysis, contract));
        properties.set("target_cue_violation", cue);
        cueValidator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(cue.toString());
        properties.set(
                "character_interaction_review",
                JSON.valueToTree(CharacterInteractionReview.schema(contract)));
        var directness = JSON.valueToTree(AnswerDirectnessViolation.schema(contract, candidate));
        properties.set("answer_directness_violation", directness);
        directnessValidator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(directness.toString());
        var progress = JSON.valueToTree(GoalProgressViolation.schema());
        properties.set("goal_progress_violation", progress);
        progressValidator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(progress.toString());
        var required = schema.putArray("required");
        properties.propertyNames().forEach(required::add);
        jsonSchema = schema.toString();
        validator =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schema);
    }

    String jsonSchema() {
        return jsonSchema;
    }

    void validate(String content) {
        var node = JSON.readTree(content);
        if (contract != null && node != null && node.path("failure_codes").isArray()) {
            for (var code : node.path("failure_codes"))
                if ("MISSING_QUESTION".equals(code.asString()))
                    throw new InvalidLlmOutputException(
                            CharacterInteractionReview.QUESTION_CONFLICT, null);
        }
        if (contract != null && node != null && node.path("revision_instruction").has("required"))
            throw new InvalidLlmOutputException(REQUIRED_CONFLICT, null);
        if (node != null
                && node.has("target_cue_violation")
                && !cueValidator.validate(node.path("target_cue_violation")).isEmpty())
            throw new InvalidLlmOutputException(TargetCueViolation.CONFLICT, null);
        if (node != null
                && node.has("answer_directness_violation")
                && !directnessValidator
                        .validate(node.path("answer_directness_violation"))
                        .isEmpty())
            throw new InvalidLlmOutputException(AnswerDirectnessViolation.CONFLICT, null);
        if (node != null
                && node.has("goal_progress_violation")
                && !progressValidator.validate(node.path("goal_progress_violation")).isEmpty())
            throw new InvalidLlmOutputException(GoalProgressViolation.CONFLICT, null);
        if (node == null || !validator.validate(node).isEmpty()) {
            if (node != null && node.path("failure_codes").isArray()) {
                for (var code : node.path("failure_codes")) {
                    if (code.isString() && !RoleplayCodes.FAILURE_CODES.contains(code.asString()))
                        throw new InvalidLlmOutputException("unknown failure code", null);
                }
            }
            throw new InvalidLlmOutputException("evaluation output does not match schema", null);
        }
    }

    void requireValidDirectness(EvaluationOutput output) {
        TargetCueViolation.requireValid(output, analysis, contract, candidate);
        CharacterInteractionReview.requireValid(output, contract, candidate);
        AnswerDirectnessViolation.requireValid(output, contract, candidate);
        if (contract != null) GoalProgressViolation.requireValid(output, analysis, candidate);
    }

    String restoreServerRequirements(String validatedContent) {
        if (contract == null) return validatedContent;
        var body = JSON.readTree(validatedContent);
        if (body.path("revision_instruction").isObject()) {
            ((ObjectNode) body.path("revision_instruction"))
                    .set(
                            "required",
                            JSON.valueToTree(
                                    List.of(
                                            "캐릭터의 1인칭 역할과 도움을 주고받는 주체를 유지한다.",
                                            "서버가 적용한 목표·지원 수준을 유지한다.",
                                            "승인된 사실을 지키고 교사식 정답 맞히기를 하지 않는다.")));
        }
        return body.toString();
    }

    static List<String> modelFailureCodes() {
        return EvaluationSchemaParts.modelFailureCodes();
    }

    List<String> allowedFailureCodes() {
        return modelFailureCodes().stream()
                .filter(code -> contract == null || !code.equals("MISSING_QUESTION"))
                .toList();
    }
}
