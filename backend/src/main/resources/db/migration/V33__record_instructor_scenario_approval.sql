-- 과거 승인본의 승인자는 추정하지 않는다. 최종 승인 시 확인한 조건만 새 행으로 기록한다.
CREATE TABLE scenario_approval (
    scenario_id UUID PRIMARY KEY REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    instructor_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    goal_title TEXT NOT NULL CHECK (btrim(goal_title) <> ''),
    scenario_level VARCHAR(2) NOT NULL CHECK (scenario_level IN ('L1', 'L2', 'L3')),
    category TEXT,
    situation_type TEXT,
    -- NULL은 미확인, 빈 배열은 확인한 항목이 없다는 뜻이다.
    character_roles JSONB CHECK (jsonb_typeof(character_roles) = 'array'),
    required_actions JSONB CHECK (jsonb_typeof(required_actions) = 'array'),
    verification_rule_version INTEGER NOT NULL CHECK (verification_rule_version > 0),
    approved_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_scenario_approval_instructor_level
    ON scenario_approval (instructor_id, scenario_level, scenario_id);

CREATE FUNCTION guard_scenario_approval() RETURNS trigger AS $$
DECLARE
    current_status TEXT;
    current_level TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Scenario approval is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT status, scenario_level INTO current_status, current_level
        FROM scenario WHERE scenario_id = NEW.scenario_id FOR SHARE;
    IF current_status IS DISTINCT FROM 'APPROVED'
        OR current_level IS DISTINCT FROM NEW.scenario_level THEN
        RAISE EXCEPTION 'Approval requires an approved scenario of the same level'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_approval_guard
    BEFORE INSERT OR UPDATE ON scenario_approval
    FOR EACH ROW EXECUTE FUNCTION guard_scenario_approval();
