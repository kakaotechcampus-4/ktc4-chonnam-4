CREATE TABLE roleplay_voice_input_state (
    session_id UUID PRIMARY KEY REFERENCES roleplay_session(session_id) ON DELETE NO ACTION,
    failure_count INTEGER NOT NULL CHECK (failure_count >= 0)
);

CREATE TABLE roleplay_voice_input_failure (
    session_id UUID NOT NULL REFERENCES roleplay_voice_input_state(session_id) ON DELETE NO ACTION,
    idempotency_key UUID NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    checkpoint_version BIGINT NOT NULL CHECK (checkpoint_version >= 0),
    failure_reason VARCHAR(30) NOT NULL
        CHECK (failure_reason IN ('LOW_CONFIDENCE', 'NO_SPEECH', 'TRANSCRIPTION_FAILED')),
    failure_count_after INTEGER NOT NULL CHECK (failure_count_after > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, idempotency_key),
    UNIQUE (session_id, failure_count_after)
);
