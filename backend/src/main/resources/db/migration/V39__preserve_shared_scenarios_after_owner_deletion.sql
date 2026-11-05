-- 공통 콘텐츠의 승인 근거와 이번 활동을 승인한 강사를 분리한다.
ALTER TABLE activity_scenario_approval
    DROP CONSTRAINT activity_scenario_approval_scenario_id_instructor_id_fkey,
    ADD CONSTRAINT fk_activity_shared_scenario_approval
        FOREIGN KEY (scenario_id) REFERENCES scenario_approval (scenario_id),
    ADD CONSTRAINT fk_activity_scenario_approving_instructor
        FOREIGN KEY (instructor_id) REFERENCES user_account (user_id);

ALTER TABLE scenario_approval
    DROP CONSTRAINT scenario_approval_instructor_id_fkey,
    ALTER COLUMN instructor_id DROP NOT NULL,
    ADD CONSTRAINT fk_shared_scenario_first_approver
        FOREIGN KEY (instructor_id) REFERENCES user_account (user_id) ON DELETE SET NULL;

ALTER TABLE scenario
    DROP CONSTRAINT scenario_class_id_fkey,
    ADD CONSTRAINT fk_scenario_class_provenance
        FOREIGN KEY (class_id) REFERENCES classroom (class_id) ON DELETE SET NULL;

-- 승인 콘텐츠는 그대로 두고 삭제된 학급의 연결 해제만 허용한다.
CREATE OR REPLACE FUNCTION prevent_approved_scenario_edit() RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('APPROVED', 'RETIRED') AND (
        (to_jsonb(NEW) - 'status' - 'class_id') IS DISTINCT FROM
            (to_jsonb(OLD) - 'status' - 'class_id')
        OR (NEW.class_id IS DISTINCT FROM OLD.class_id AND NEW.class_id IS NOT NULL)
        OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED', 'RETIRED'))
        OR (OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED')
    ) THEN
        RAISE EXCEPTION 'Approved scenario versions are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 계정 삭제에 따른 최초 승인자 연결 해제 외에는 승인 조건을 바꾸지 못한다.
CREATE OR REPLACE FUNCTION guard_scenario_approval() RETURNS trigger AS $$
DECLARE
    current_status TEXT;
    current_level TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.instructor_id IS NOT NULL AND NEW.instructor_id IS NULL
            AND (to_jsonb(NEW) - 'instructor_id') = (to_jsonb(OLD) - 'instructor_id') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'Scenario approval is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.instructor_id IS NULL THEN
        RAISE EXCEPTION 'A new approval requires its approver' USING ERRCODE = '23514';
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
