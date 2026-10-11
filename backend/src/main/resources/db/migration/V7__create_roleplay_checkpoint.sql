ALTER TABLE activity ADD CONSTRAINT uq_activity_child UNIQUE (activity_id, child_id);

CREATE TABLE roleplay_session (
    session_id UUID PRIMARY KEY,
    activity_id UUID NOT NULL,
    child_id UUID NOT NULL,
    scenario_id UUID NOT NULL,
    scenario_version INTEGER NOT NULL CHECK (scenario_version > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','PAUSED','COMPLETED')),
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    last_turn_number INTEGER NOT NULL DEFAULT 0 CHECK (last_turn_number >= 0),
    last_turn_id UUID,
    current_micro_goal_id UUID,
    current_support_level VARCHAR(2) CHECK (current_support_level IN ('S0','S1','S2','S3')),
    last_activity_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (activity_id, child_id) REFERENCES activity(activity_id, child_id) ON DELETE CASCADE,
    CHECK ((last_turn_number = 0 AND last_turn_id IS NULL) OR (last_turn_number > 0 AND last_turn_id IS NOT NULL))
);

CREATE TABLE roleplay_turn (
    turn_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES roleplay_session(session_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    turn_number INTEGER NOT NULL CHECK (turn_number > 0),
    candidate_id UUID NOT NULL,
    micro_goal_id UUID NOT NULL,
    support_level VARCHAR(2) NOT NULL CHECK (support_level IN ('S0','S1','S2','S3')),
    response_text TEXT NOT NULL CHECK (length(trim(response_text)) > 0),
    canonical_utterance TEXT,
    checkpoint_version BIGINT NOT NULL CHECK (checkpoint_version > 0),
    committed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, idempotency_key),
    UNIQUE (session_id, turn_number),
    UNIQUE (session_id, candidate_id),
    UNIQUE (session_id, turn_id)
);

ALTER TABLE roleplay_session ADD CONSTRAINT fk_session_last_turn
    FOREIGN KEY (session_id, last_turn_id) REFERENCES roleplay_turn(session_id, turn_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_roleplay_session_activity ON roleplay_session(activity_id);
CREATE INDEX idx_roleplay_session_cleanup ON roleplay_session(status, last_activity_at);
