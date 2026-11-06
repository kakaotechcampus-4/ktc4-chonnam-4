ALTER TABLE roleplay_session
    ADD COLUMN completion_kind VARCHAR(40);

UPDATE roleplay_session SET completion_kind = 'NORMAL' WHERE status = 'COMPLETED';

ALTER TABLE roleplay_session
    ADD CONSTRAINT ck_roleplay_completion_kind
    CHECK (
        (status = 'COMPLETED' AND completion_kind IN ('NORMAL', 'TECHNICAL_STORED_TURN_LIMIT'))
        OR (status <> 'COMPLETED' AND completion_kind IS NULL)
    );
