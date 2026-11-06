package com.neuringo.neuringobe.scenario.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 완료한 역할극 한 건의 목표별 결과(03 VS-012 "목표별 시작·현재·최종 지원 수준", VS-016 입력).
 *
 * <p>finalSupport 는 도달했으면 도달한 단계, 도달하지 못했으면 마지막으로 쓴 단계다. 지원은 올라가기만 하지 않으므로(힌트 없는 연속 달성 시 낮춤) 시작
 * 단계보다 낮을 수도 있다.
 */
public record GoalOutcome(
        UUID goalId, SupportLevel startSupport, SupportLevel finalSupport, Result result) {

    public enum Result {
        REACHED,
        NOT_REACHED,
        /** 기술 오류(STT·네트워크 등). 아동의 어려움이 아니므로 난이도·추천 근거에서 뺀다. */
        EXCLUDED_TECHNICAL_ERROR,
        EXCLUDED_INTERRUPTED,
        EXCLUDED_REFUSED;

        public boolean excluded() {
            return this != REACHED && this != NOT_REACHED;
        }
    }

    public GoalOutcome {
        Objects.requireNonNull(goalId, "goalId must not be null");
        Objects.requireNonNull(startSupport, "startSupport must not be null");
        Objects.requireNonNull(finalSupport, "finalSupport must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }

    public boolean valid() {
        return !result.excluded();
    }

    /** S0~S2 에서 도달. S3 도달(정답 제공)은 포함하지 않는다. */
    public boolean reachedWithoutAnswer() {
        return result == Result.REACHED && finalSupport != SupportLevel.S3;
    }

    /** VS-016 하향 조건의 "S3·미도달 목표". */
    public boolean neededAnswerOrMissed() {
        return result == Result.NOT_REACHED
                || (result == Result.REACHED && finalSupport == SupportLevel.S3);
    }

    /** 시작 단계보다 지원을 더 받지 않고 도달. */
    public boolean reachedWithoutExtraSupport() {
        return reachedWithoutAnswer() && finalSupport.extraStepsFrom(startSupport) <= 0;
    }
}
