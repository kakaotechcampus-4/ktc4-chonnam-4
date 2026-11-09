package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.io.IOException;
import java.nio.file.Path;

/** 서버가 소유한 파일의 실제 음성 형식과 길이를 확인한다. */
@FunctionalInterface
public interface RoleplayAudioInspector {
    void requireValid(Path file, AudioFormat format, RoleplayTurnDeadline deadline)
            throws IOException;

    final class Unavailable extends IllegalStateException {
        private final String inputFingerprint;

        public Unavailable() {
            this(null);
        }

        private Unavailable(String fingerprint) {
            super("음성 파일 검증 서비스를 사용할 수 없습니다.");
            this.inputFingerprint = fingerprint;
        }

        public Unavailable withFingerprint(String fingerprint) {
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("음성 입력 지문이 올바르지 않습니다.");
            var copy = new Unavailable(fingerprint);
            for (var suppressed : getSuppressed()) copy.addSuppressed(suppressed);
            return copy;
        }

        public String inputFingerprint() {
            return inputFingerprint;
        }
    }
}
