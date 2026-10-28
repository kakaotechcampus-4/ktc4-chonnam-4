-- 과거 턴의 입력 상태는 다음 질문에서 추측하지 않고 NULL로 유지한다.
ALTER TABLE roleplay_turn
    ADD COLUMN input_micro_goal_id UUID,
    ADD COLUMN input_support_level VARCHAR(2),
    ADD COLUMN input_support_turn INTEGER,
    ADD COLUMN input_prompt_count INTEGER,
    ADD COLUMN input_answer_revealed BOOLEAN,
    ADD CONSTRAINT ck_roleplay_turn_input_context CHECK (
        (input_micro_goal_id IS NULL AND input_support_level IS NULL
            AND input_support_turn IS NULL AND input_prompt_count IS NULL AND input_answer_revealed IS NULL)
        OR (
            input_micro_goal_id IS NOT NULL AND input_support_level IS NOT NULL
            AND input_support_turn IS NOT NULL AND input_prompt_count IS NOT NULL AND input_answer_revealed IS NOT NULL
            AND input_support_level IN ('S0', 'S1', 'S2', 'S3')
            AND input_support_turn >= 1 AND input_prompt_count >= 0
        )
    );
