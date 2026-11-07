package com.neuringo.neuringobe.report.domain;

/**
 * 퀴즈 집계 결과(06 확정 정책 "전체 정답 문항 수 ÷ 전체 유효 문항 수"). 기술 오류 문항은 validQuestionCount 에 들어가지 않는다.
 *
 * @param resolvedAfterHintCount 첫 응답은 틀렸지만 힌트 뒤에 맞힌 문항 수. 첫 정답에는 세지 않는다.
 */
public record QuizEvidence(
        int validQuestionCount, int correctQuestionCount, int resolvedAfterHintCount) {

    public QuizEvidence {
        if (validQuestionCount < 0
                || correctQuestionCount < 0
                || correctQuestionCount > validQuestionCount
                || resolvedAfterHintCount < 0
                || resolvedAfterHintCount > validQuestionCount - correctQuestionCount) {
            throw new IllegalArgumentException("quiz counts are inconsistent");
        }
    }
}
