package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.ReportItem;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReview;
import com.neuringo.neuringobe.ai.application.structured.output.ReportReviewDecision;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 리포트 프롬프트 v1 출력의 형식 검사. 비어 있는 문장, 판정과 어긋난 실패 코드, 허용하지 않은 코드를 InvalidLlmOutputException 으로 막는다.
 *
 * <p>근거 ID 가 맞는지·진단 표현이 있는지는 여기서 막지 않는다. 그건 고쳐 쓰라고 되돌려 보낼 품질 문제라서 ReportQualityRule 이 본다. 예외 메시지에는
 * 모델 출력 내용을 넣지 않는다.
 */
final class LearningReportOutputParsers {

    private static final Set<String> SECTIONS =
            Set.of("observations", "effective_supports", "difficult_points");

    private LearningReportOutputParsers() {}

    static LlmOutputParser<ReportDraft> draft(ObjectMapper objectMapper) {
        LlmOutputParser<ReportDraft> delegate =
                new JacksonLlmOutputParser<>(objectMapper, ReportDraft.class);
        return content -> {
            ReportDraft draft = delegate.parse(RoleplayOutputParsers.stripCodeFence(content));
            check(!draft.summary().isBlank(), "summary must not be blank");
            check(
                    draft.items().map(ReportItem::text).noneMatch(String::isBlank),
                    "report item text must not be blank");
            return draft;
        };
    }

    static LlmOutputParser<ReportReview> review(ObjectMapper objectMapper) {
        LlmOutputParser<ReportReview> delegate =
                new JacksonLlmOutputParser<>(objectMapper, ReportReview.class);
        return content -> {
            ReportReview review =
                    delegate.parse(
                            normalizeRevision(
                                    objectMapper, RoleplayOutputParsers.stripCodeFence(content)));
            check(
                    ReportCodes.ALL.containsAll(review.failureCodes()),
                    "unknown report failure code");
            check(
                    review.itemChecks().stream()
                            .allMatch(
                                    item -> SECTIONS.contains(item.section()) && item.index() >= 0),
                    "unknown item_checks section");
            if (review.decision() == ReportReviewDecision.PASS) {
                check(review.failureCodes().isEmpty(), "PASS must not carry failure codes");
            } else {
                check(!review.failureCodes().isEmpty(), "REVISE needs failure codes");
            }
            return review;
        };
    }

    /**
     * 고칠 점(revision_instruction)의 모양만 맞춘다. 2026-10-29 실측에서 GPT-4.1 mini·Claude Haiku·Sonnet
     * 5.5·Gemini 4 Argon 이 모두 이 칸을 문장 하나("keep: … change: …")로 보내거나 keep·change 를 배열 대신 문자열로 보냈다.
     * 내용은 다음 초안에 넘길 수 있는 지시라서, 문장은 change 하나로, 문자열 칸은 항목 하나짜리 배열로 바꾼다. 판정·점수·실패 코드는 바꾸지 않고 그대로 엄격하게
     * 검사한다.
     */
    private static String normalizeRevision(ObjectMapper objectMapper, String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException exception) {
            return json;
        }
        if (!(root instanceof ObjectNode object)) return json;
        JsonNode instruction = object.get("revision_instruction");
        if (instruction == null) return json;
        if (instruction.isString()) {
            ObjectNode replaced = objectMapper.createObjectNode();
            replaced.putArray("keep");
            replaced.putArray("change").add(instruction.asString());
            replaced.putArray("avoid");
            replaced.putArray("required");
            object.set("revision_instruction", replaced);
        } else if (instruction instanceof ObjectNode fields) {
            for (String name : List.of("keep", "change", "avoid", "required")) {
                JsonNode value = fields.get(name);
                if (value != null && value.isString()) {
                    String text = value.asString();
                    fields.putArray(name).add(text);
                }
            }
        } else {
            return json;
        }
        return objectMapper.writeValueAsString(object);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new InvalidLlmOutputException(message, null);
        }
    }
}
