-- 공용 자료의 학급을 추측해 채우지 않는다. 학급·목표 해시가 있는 승인본만 자동 재사용한다.
ALTER TABLE scenario
    ADD COLUMN class_id UUID REFERENCES classroom (class_id) ON DELETE CASCADE,
    ADD COLUMN scenario_version INTEGER NOT NULL DEFAULT 1 CHECK (scenario_version > 0);

CREATE INDEX idx_scenario_reuse
    ON scenario (class_id, goal_content_hash, scenario_level, created_at, scenario_id)
    WHERE status = 'APPROVED';

-- 승인 후에는 폐기만 허용하고, 내용 수정은 새 시나리오 버전으로 저장한다.
CREATE FUNCTION prevent_approved_scenario_edit() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('APPROVED', 'RETIRED') AND (
        (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status')
        OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED', 'RETIRED'))
        OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
    ) THEN
        RAISE EXCEPTION 'Approved scenario versions are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_immutable
    BEFORE UPDATE ON scenario
    FOR EACH ROW EXECUTE FUNCTION prevent_approved_scenario_edit();

-- 작은 목표도 승인 내용이므로 부모 승인과의 경합을 잠그고 직접 변경을 막는다.
CREATE FUNCTION prevent_approved_micro_goal_edit() RETURNS trigger AS $$
DECLARE
    parent_status TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        SELECT status INTO parent_status FROM scenario
            WHERE scenario_id = OLD.scenario_id FOR SHARE;
        IF parent_status IN ('APPROVED', 'RETIRED') THEN
            RAISE EXCEPTION 'Approved micro goals are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT status INTO parent_status FROM scenario
            WHERE scenario_id = NEW.scenario_id FOR SHARE;
        IF parent_status IN ('APPROVED', 'RETIRED') THEN
            RAISE EXCEPTION 'Approved micro goals are immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_micro_goal_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON scenario_micro_goal
    FOR EACH ROW EXECUTE FUNCTION prevent_approved_micro_goal_edit();
