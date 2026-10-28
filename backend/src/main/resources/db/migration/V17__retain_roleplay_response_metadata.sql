-- 과거 턴은 종류·전략을 추측하지 않고 둘 다 NULL로 유지한다.
ALTER TABLE roleplay_turn
    ADD COLUMN response_type VARCHAR(32),
    ADD COLUMN strategy_used VARCHAR(32),
    ADD CONSTRAINT ck_roleplay_turn_response_metadata CHECK (
        (response_type IS NULL AND strategy_used IS NULL)
        OR (
            response_type IS NOT NULL AND strategy_used IS NOT NULL
            AND response_type IN ('GUIDING_QUESTION', 'ANSWER_REVEAL', 'REDIRECT', 'CONFIRMATION', 'CLOSING')
            AND strategy_used IN ('PROBE_EVENT', 'PROBE_EMOTION', 'PROBE_CAUSE', 'PROBE_ACTION',
                'REDIRECT_TO_SCENARIO', 'CONFIRM_UNDERSTANDING', 'ADVANCE_GOAL', 'CLOSE_ROLEPLAY')
        )
    );
