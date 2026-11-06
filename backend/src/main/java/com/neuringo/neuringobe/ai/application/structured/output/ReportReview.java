package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * 리포트 초안 평가. 평가 모델은 초안을 고쳐 쓰지 않고 항목별 확인·점수·고칠 점만 낸다. 통과 여부는 오케스트레이터가 확인 결과와 점수 기준으로 다시 본다.
 *
 * <p>itemChecks 는 report-review/v2 부터 있다. 점수를 매기기 전에 항목마다 근거와 칸을 확인하게 해서, 확인 없이 만점을 주는 것을
 * 줄인다(2026-10-29 실측에서 GPT-4.1 mini 자기 평가가 14번 모두 만점이었다).
 *
 * @param itemChecks 항목별 확인. 없으면 빈 목록
 * @param revisionInstruction REVISE 일 때 다음 초안에 줄 지시
 */
public record ReportReview(
        @JsonProperty("item_checks") List<ItemCheck> itemChecks,
        ReportReviewDecision decision,
        ReportReviewScores scores,
        @JsonProperty("failure_codes") List<String> failureCodes,
        @JsonProperty("revision_instruction") RevisionInstruction revisionInstruction) {

    /**
     * 초안 항목 하나의 확인 결과.
     *
     * @param section observations·effective_supports·difficult_points
     * @param index 그 목록 안의 순서(0 부터)
     * @param grounded 단 근거가 그 문장을 실제로 뒷받침하는가
     * @param rightSection 그 칸에 맞는 내용인가(어려운 지점에 혼자 해낸 목표가 없는가 등)
     */
    public record ItemCheck(
            String section,
            Integer index,
            Boolean grounded,
            @JsonProperty("right_section") Boolean rightSection) {

        public ItemCheck {
            Objects.requireNonNull(section, "section must not be null");
            Objects.requireNonNull(index, "index must not be null");
            Objects.requireNonNull(grounded, "grounded must not be null");
            Objects.requireNonNull(rightSection, "rightSection must not be null");
        }

        public boolean passed() {
            return grounded && rightSection;
        }
    }

    public ReportReview {
        itemChecks = itemChecks == null ? List.of() : List.copyOf(itemChecks);
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(scores, "scores must not be null");
        Objects.requireNonNull(failureCodes, "failureCodes must not be null");
        failureCodes = List.copyOf(failureCodes);
        if (decision == ReportReviewDecision.REVISE && revisionInstruction == null) {
            throw new IllegalArgumentException("revisionInstruction is required for REVISE");
        }
    }

    public ReportReview(
            ReportReviewDecision decision,
            ReportReviewScores scores,
            List<String> failureCodes,
            RevisionInstruction revisionInstruction) {
        this(List.of(), decision, scores, failureCodes, revisionInstruction);
    }

    public boolean everyItemChecked() {
        return itemChecks.stream().allMatch(ItemCheck::passed);
    }
}
