package com.neuringo.neuringobe.ai.application.structured.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * 평가 기준별 점수 0~4. normalized() 는 다섯 기준의 합을 0~1 로 맞춘 값이라 프롬프트 버전끼리 비교할 때 쓴다.
 *
 * <ul>
 *   <li>grounded: 모든 문장이 입력 근거에 기대고, 없는 행동·감정을 더하지 않는다
 *   <li>nonDiagnostic: 진단·성향·능력 단정이 없다
 *   <li>technicalSeparation: 기술 오류를 아동의 어려움으로 쓰지 않는다
 *   <li>actionable: 강사가 다음 활동에 쓸 수 있는 지원·어려운 지점이 있다
 *   <li>readable: 짧고 쉬운 문장이다
 * </ul>
 */
public record ReportReviewScores(
        Integer grounded,
        @JsonProperty("non_diagnostic") Integer nonDiagnostic,
        @JsonProperty("technical_separation") Integer technicalSeparation,
        Integer actionable,
        Integer readable) {

    public static final int MAX_SCORE = 4;

    public ReportReviewScores {
        for (Integer score :
                new Integer[] {
                    grounded, nonDiagnostic, technicalSeparation, actionable, readable
                }) {
            Objects.requireNonNull(score, "every score is required");
            if (score < 0 || score > MAX_SCORE) {
                throw new IllegalArgumentException("score must be within 0.." + MAX_SCORE);
            }
        }
    }

    public int min() {
        return all().min().orElseThrow();
    }

    public double normalized() {
        return all().sum() / (double) (MAX_SCORE * 5);
    }

    private IntStream all() {
        return IntStream.of(grounded, nonDiagnostic, technicalSeparation, actionable, readable);
    }
}
