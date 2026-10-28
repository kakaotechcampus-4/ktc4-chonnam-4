-- V14·V15는 목표 조건·시나리오 PR에서 사용한다. 이 변경은 그 뒤에 적용한다.
ALTER TABLE roleplay_session ADD CONSTRAINT uq_roleplay_session_scenario
    UNIQUE (session_id, scenario_id, scenario_version);
ALTER TABLE roleplay_turn ADD CONSTRAINT uq_roleplay_turn_checkpoint
    UNIQUE (session_id, turn_id, checkpoint_version);

CREATE TABLE roleplay_goal_assessment (
    session_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    checkpoint_version BIGINT NOT NULL,
    scenario_id UUID NOT NULL,
    scenario_version INTEGER NOT NULL,
    approval_reference TEXT NOT NULL CHECK (length(trim(approval_reference)) > 0),
    analysis_contract_version TEXT NOT NULL CHECK (length(trim(analysis_contract_version)) > 0),
    evaluation_contract_version TEXT NOT NULL CHECK (length(trim(evaluation_contract_version)) > 0),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('ACHIEVED', 'IN_PROGRESS')),
    PRIMARY KEY (session_id, turn_id),
    FOREIGN KEY (session_id, turn_id, checkpoint_version)
        REFERENCES roleplay_turn(session_id, turn_id, checkpoint_version),
    FOREIGN KEY (session_id, scenario_id, scenario_version)
        REFERENCES roleplay_session(session_id, scenario_id, scenario_version)
);

CREATE TABLE roleplay_goal_observation (
    session_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    micro_goal_id UUID NOT NULL,
    criterion_reference TEXT NOT NULL CHECK (length(trim(criterion_reference)) > 0),
    observation_status VARCHAR(20) NOT NULL CHECK (observation_status IN (
        'EXPLICIT', 'IMPLICIT', 'NOT_EXPRESSED', 'MISUNDERSTOOD', 'CONTRADICTORY')),
    PRIMARY KEY (session_id, turn_id, micro_goal_id, criterion_reference),
    FOREIGN KEY (session_id, turn_id) REFERENCES roleplay_goal_assessment(session_id, turn_id)
);

CREATE TABLE roleplay_goal_decision_basis (
    session_id UUID NOT NULL,
    decision_turn_id UUID NOT NULL,
    observation_turn_id UUID NOT NULL,
    micro_goal_id UUID NOT NULL,
    criterion_reference TEXT NOT NULL,
    PRIMARY KEY (session_id, decision_turn_id, observation_turn_id, micro_goal_id, criterion_reference),
    FOREIGN KEY (session_id, decision_turn_id) REFERENCES roleplay_goal_assessment(session_id, turn_id),
    FOREIGN KEY (session_id, observation_turn_id, micro_goal_id, criterion_reference)
        REFERENCES roleplay_goal_observation(session_id, turn_id, micro_goal_id, criterion_reference)
);

-- 최신 관찰을 이력에서 읽어 별도 상태 행과 어긋나지 않게 한다. 목표 달성 여부를 계산하는 뷰는 아니다.
CREATE VIEW roleplay_goal_evidence_current AS
SELECT DISTINCT ON (o.session_id, o.micro_goal_id, o.criterion_reference)
    o.session_id, o.micro_goal_id, o.criterion_reference, o.observation_status,
    o.turn_id, a.checkpoint_version
FROM roleplay_goal_observation o
JOIN roleplay_goal_assessment a USING (session_id, turn_id)
ORDER BY o.session_id, o.micro_goal_id, o.criterion_reference, a.checkpoint_version DESC;
