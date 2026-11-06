ALTER TABLE scenario_approval ADD CONSTRAINT uq_scenario_approval_owner UNIQUE (scenario_id, instructor_id);

CREATE TABLE activity_scenario_approval (
    activity_id UUID PRIMARY KEY REFERENCES activity (activity_id) ON DELETE CASCADE,
    review_id UUID NOT NULL,
    child_id UUID NOT NULL,
    goal_id UUID NOT NULL,
    goal_content_hash VARCHAR(64) NOT NULL,
    instructor_id UUID NOT NULL,
    scenario_id UUID NOT NULL,
    scenario_version INTEGER NOT NULL CHECK (scenario_version > 0),
    scenario_level VARCHAR(2) NOT NULL CHECK (scenario_level IN ('L1', 'L2', 'L3')),
    content_fingerprint VARCHAR(64) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (scenario_id, instructor_id) REFERENCES scenario_approval (scenario_id, instructor_id)
);

ALTER TABLE scenario_presentation_stat
    ADD COLUMN reuse_count BIGINT NOT NULL DEFAULT 0 CHECK (reuse_count >= 0),
    ADD COLUMN last_reused_at TIMESTAMPTZ;
ALTER TABLE scenario_presentation_daily
    ADD COLUMN reuse_count BIGINT NOT NULL DEFAULT 0 CHECK (reuse_count >= 0),
    DROP CONSTRAINT scenario_presentation_daily_presentation_count_check,
    ADD CONSTRAINT ck_scenario_daily_counts CHECK (
        presentation_count >= 0 AND (presentation_count > 0 OR reuse_count > 0)
    );

-- 새 승인 경로는 활동별 근거를 검사하고, 기존 배정의 검증 조건은 유지한다.
CREATE VIEW activity_approved_scenario AS
SELECT a.activity_id, a.child_id, a.goal_id, s.scenario_id, s.scenario_version, s.scenario_level
FROM activity a JOIN child c ON c.child_id = a.child_id
JOIN learning_goal g ON g.goal_id = a.goal_id AND g.child_id = a.child_id
JOIN scenario s ON s.scenario_id = a.scenario_id AND s.status = 'APPROVED'
WHERE EXISTS (
    SELECT 1 FROM activity_scenario_approval p
    WHERE p.activity_id = a.activity_id AND p.child_id = a.child_id AND p.goal_id = a.goal_id
        AND p.goal_content_hash = g.content_hash AND p.scenario_id = s.scenario_id
        AND p.scenario_version = s.scenario_version AND p.scenario_level = s.scenario_level
) OR (
    NOT EXISTS (SELECT 1 FROM activity_scenario_approval p WHERE p.activity_id = a.activity_id)
    AND s.class_id = c.class_id AND s.goal_content_hash = g.content_hash
);
