-- 기존 세션을 삭제하거나 합치지 않고, 시작 요청 키가 같은 세션을 가리키도록 보존한다.
ALTER TABLE roleplay_session
    ADD CONSTRAINT uq_roleplay_session_identity UNIQUE (session_id, activity_id, child_id);

CREATE TABLE roleplay_start_request (
    request_key UUID PRIMARY KEY,
    activity_id UUID NOT NULL,
    child_id UUID NOT NULL,
    session_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (session_id, activity_id, child_id)
        REFERENCES roleplay_session (session_id, activity_id, child_id) ON DELETE CASCADE
);

CREATE INDEX idx_roleplay_start_request_session ON roleplay_start_request (session_id);
