ALTER TABLE roleplay_turn ADD CONSTRAINT uq_roleplay_turn_response_support
    UNIQUE (session_id, turn_id, micro_goal_id, support_level);

ALTER TABLE roleplay_goal_assessment ADD COLUMN support_application_kind VARCHAR(16)
    CHECK (support_application_kind IN ('DECREASED', 'AT_MINIMUM'));

CREATE TABLE roleplay_support_application (
    session_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    target_micro_goal_id UUID NOT NULL,
    policy_version TEXT NOT NULL CHECK (length(trim(policy_version)) > 0),
    required_independent_goals INTEGER NOT NULL CHECK (required_independent_goals > 0),
    in_progress_handling VARCHAR(24) NOT NULL CHECK (in_progress_handling IN ('RESET_SEQUENCE', 'KEEP_SEQUENCE')),
    base_support_level VARCHAR(2) NOT NULL CHECK (base_support_level IN ('S0','S1','S2','S3')),
    applied_support_level VARCHAR(2) NOT NULL CHECK (applied_support_level IN ('S0','S1','S2')),
    application_kind VARCHAR(16) NOT NULL CHECK (application_kind IN ('DECREASED','AT_MINIMUM')),
    PRIMARY KEY (session_id, turn_id),
    UNIQUE (session_id, turn_id, application_kind),
    FOREIGN KEY (session_id, turn_id) REFERENCES roleplay_goal_assessment(session_id, turn_id),
    FOREIGN KEY (session_id, turn_id, target_micro_goal_id, applied_support_level)
        REFERENCES roleplay_turn(session_id, turn_id, micro_goal_id, support_level),
    CHECK (substring(applied_support_level, 2)::integer = greatest(0, substring(base_support_level, 2)::integer - 1)),
    CHECK ((application_kind='AT_MINIMUM' AND base_support_level='S0')
        OR (application_kind='DECREASED' AND base_support_level<>'S0')),
    CHECK (policy_version<>'SUPPORT_DECREASE_V1'
        OR (required_independent_goals=2 AND in_progress_handling='RESET_SEQUENCE'))
);

-- 판정과 적용 기록은 같은 트랜잭션 안에서 모두 저장돼야 한다.
ALTER TABLE roleplay_goal_assessment ADD CONSTRAINT fk_roleplay_support_application
    FOREIGN KEY (session_id, turn_id, support_application_kind)
    REFERENCES roleplay_support_application(session_id, turn_id, application_kind)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE roleplay_support_application_basis (
    session_id UUID NOT NULL,
    application_turn_id UUID NOT NULL,
    evidence_turn_id UUID NOT NULL,
    micro_goal_id UUID NOT NULL,
    criterion_reference TEXT NOT NULL,
    PRIMARY KEY (session_id, application_turn_id, evidence_turn_id, micro_goal_id, criterion_reference),
    FOREIGN KEY (session_id, application_turn_id) REFERENCES roleplay_support_application(session_id, turn_id),
    FOREIGN KEY (session_id, evidence_turn_id, micro_goal_id, criterion_reference)
        REFERENCES roleplay_input_goal_basis(session_id, turn_id, micro_goal_id, criterion_reference)
);
