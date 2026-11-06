ALTER TABLE roleplay_voice_input_failure
    DROP CONSTRAINT roleplay_voice_input_failure_failure_reason_check;

ALTER TABLE roleplay_voice_input_failure
    ADD CONSTRAINT ck_roleplay_voice_failure_reason CHECK (
        failure_reason IN ('LOW_CONFIDENCE', 'NO_SPEECH', 'TRANSCRIPTION_FAILED',
            'EMPTY_UPLOAD', 'UNSUPPORTED_AUDIO', 'INVALID_AUDIO', 'AUDIO_INSPECTION_UNAVAILABLE',
            'UPLOAD_TOO_LARGE', 'AUDIO_TOO_LONG', 'UNKNOWN_CONFIDENCE', 'INVALID_INPUT')
    );
