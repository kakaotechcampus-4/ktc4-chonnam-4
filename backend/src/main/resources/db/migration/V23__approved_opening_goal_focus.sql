-- 첫 질문의 목표는 목록 순서로 추측하지 않고 승인 자료에 명시한다.
ALTER TABLE scenario_micro_goal
    ADD CONSTRAINT uq_scenario_micro_goal_scope UNIQUE (scenario_id, micro_goal_id);

CREATE TABLE scenario_opening_focus (
    scenario_id UUID NOT NULL REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    support_level VARCHAR(2) NOT NULL CHECK (support_level IN ('S0', 'S1', 'S2', 'S3')),
    micro_goal_id UUID NOT NULL,
    PRIMARY KEY (scenario_id, support_level),
    FOREIGN KEY (scenario_id, micro_goal_id)
        REFERENCES scenario_micro_goal (scenario_id, micro_goal_id) ON DELETE CASCADE
);

-- 첫 질문과 목표의 연결도 승인 후에는 바꾸지 않는다.
CREATE TRIGGER trg_scenario_opening_focus_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON scenario_opening_focus
    FOR EACH ROW EXECUTE FUNCTION prevent_approved_micro_goal_edit();
