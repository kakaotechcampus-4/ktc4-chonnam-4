-- 시작 문구는 승인 버전에 고정하며 재전송으로 메시지를 새로 만들지 않는다.
CREATE TABLE roleplay_start_message (
    session_id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL,
    scenario_version INTEGER NOT NULL CHECK (scenario_version > 0),
    message_id UUID NOT NULL UNIQUE,
    opening_text TEXT NOT NULL CHECK (length(trim(opening_text)) > 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (session_id, scenario_id, scenario_version)
        REFERENCES roleplay_session (session_id, scenario_id, scenario_version) ON DELETE CASCADE
);
