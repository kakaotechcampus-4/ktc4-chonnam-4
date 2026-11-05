-- 과거 실패의 판정 근거는 추측하지 않고 비워 둔다.
ALTER TABLE roleplay_voice_input_failure
    ADD COLUMN stt_confidence DOUBLE PRECISION,
    ADD COLUMN stt_confidence_threshold DOUBLE PRECISION,
    ADD CONSTRAINT ck_roleplay_voice_failure_confidence CHECK (
        (stt_confidence IS NULL AND stt_confidence_threshold IS NULL)
        OR (
            stt_confidence IS NOT NULL AND stt_confidence_threshold IS NOT NULL
            AND failure_reason = 'LOW_CONFIDENCE'
            AND stt_confidence >= 0 AND stt_confidence <= 1
            AND stt_confidence_threshold >= 0 AND stt_confidence_threshold <= 1
            AND stt_confidence <= stt_confidence_threshold
        )
    );
