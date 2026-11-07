-- 과거 판정의 도달 방식·지원 변경 이유는 추측해 채우지 않는다.
ALTER TABLE roleplay_goal_assessment
    ADD COLUMN input_goal_outcome VARCHAR(32),
    ADD COLUMN support_decision VARCHAR(24),
    ADD CONSTRAINT uq_roleplay_assessment_input_goal UNIQUE (session_id, turn_id, input_micro_goal_id),
    ADD CONSTRAINT ck_roleplay_assessment_input_evidence CHECK (
        (input_goal_outcome IS NULL AND support_decision IS NULL)
        OR (input_micro_goal_id IS NOT NULL AND input_learning_state IS NOT NULL
            AND input_goal_outcome IS NOT NULL AND support_decision IS NOT NULL
            AND input_goal_outcome IN ('ACHIEVED_WITHOUT_HINT', 'ACHIEVED_WITH_SUPPORT',
                'SUPPORTED_NOT_ACHIEVED', 'IN_PROGRESS', 'NOT_ASSESSABLE')
            AND support_decision IN ('MAINTAINED', 'INCREASED', 'NEXT_GOAL_START', 'CLOSING'))
    );

CREATE TABLE roleplay_input_goal_basis (
    session_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    micro_goal_id UUID NOT NULL,
    criterion_reference TEXT NOT NULL,
    PRIMARY KEY (session_id, turn_id, micro_goal_id, criterion_reference),
    FOREIGN KEY (session_id, turn_id, micro_goal_id)
        REFERENCES roleplay_goal_assessment(session_id, turn_id, input_micro_goal_id),
    FOREIGN KEY (session_id, turn_id, micro_goal_id, criterion_reference)
        REFERENCES roleplay_goal_observation(session_id, turn_id, micro_goal_id, criterion_reference)
);
