package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult.InputFailure;
import java.util.Objects;

/** 입력 실패 누적만 계산하며 학습 성취·지원 수준·턴 상태는 변경하지 않는다. */
public record RoleplayVoiceFailureProgress(int failureCount) {
    private static final int TEXT_RECOMMENDATION_THRESHOLD = 3;

    public RoleplayVoiceFailureProgress {
        if (failureCount < 0) throw new IllegalArgumentException("음성 입력 실패 횟수는 음수일 수 없습니다.");
    }

    public static RoleplayVoiceFailureProgress initial() {
        return new RoleplayVoiceFailureProgress(0);
    }

    /** 재전송·서버 내부 재시도가 아닌 새 입력의 최종 실패에만 호출한다. 중복 검사는 저장 경계에서 맡는다. */
    public Update recordNewFailure(InputFailure reason) {
        Objects.requireNonNull(reason);
        return switch (reason) {
            case LOW_CONFIDENCE,
                            NO_SPEECH,
                            TRANSCRIPTION_FAILED,
                            EMPTY_UPLOAD,
                            UNSUPPORTED_AUDIO,
                            INVALID_AUDIO,
                            AUDIO_INSPECTION_UNAVAILABLE,
                            UPLOAD_TOO_LARGE,
                            AUDIO_TOO_LONG,
                            UNKNOWN_CONFIDENCE,
                            INVALID_INPUT ->
                    new Update(
                            new RoleplayVoiceFailureProgress(Math.addExact(failureCount, 1)),
                            CountingStatus.COUNTED);
        };
    }

    /** 텍스트 권유 여부이며 음성 입력을 차단하는 조건으로 사용하지 않는다. */
    public boolean textRecommended() {
        return failureCount >= TEXT_RECOMMENDATION_THRESHOLD;
    }

    public enum CountingStatus {
        COUNTED,
        POLICY_REQUIRED
    }

    public record Update(RoleplayVoiceFailureProgress progress, CountingStatus status) {
        public Update {
            Objects.requireNonNull(progress);
            Objects.requireNonNull(status);
        }
    }
}
