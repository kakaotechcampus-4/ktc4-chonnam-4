-- 과거 작은 목표의 판정은 다음 질문이나 관찰 결과에서 추측해 채우지 않는다.
ALTER TABLE roleplay_turn
    ADD CONSTRAINT uq_roleplay_turn_input_goal UNIQUE (session_id, turn_id, input_micro_goal_id);

ALTER TABLE roleplay_goal_assessment
    ADD COLUMN input_micro_goal_id UUID,
    ADD COLUMN input_learning_state VARCHAR(32),
    ADD CONSTRAINT ck_roleplay_assessment_input_state CHECK (
        (input_micro_goal_id IS NULL AND input_learning_state IS NULL)
        OR (input_micro_goal_id IS NOT NULL AND input_learning_state IS NOT NULL
            AND input_learning_state IN ('GOAL_ACHIEVED', 'CORRECT_BUT_SHALLOW',
                'PARTIAL_UNDERSTANDING', 'MISCONCEPTION', 'OFF_TOPIC', 'HELP_NEEDED', 'NOT_ASSESSABLE'))
    ),
    ADD CONSTRAINT fk_roleplay_assessment_input_goal
        FOREIGN KEY (session_id, turn_id, input_micro_goal_id)
        REFERENCES roleplay_turn (session_id, turn_id, input_micro_goal_id);
