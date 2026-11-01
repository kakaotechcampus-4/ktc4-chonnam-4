-- 시나리오 상태를 팀 명세의 네 가지로 바꾸고, 학급 칸을 없애고, 승인 시각을 남긴다.
-- - 상태: DRAFT(초안) / REVISION_REQUIRED(고쳐 오라고 판정) / REJECTED(쓰지 않기로 판정) / APPROVED(재사용 후보).
--   고친 판이 승인돼도 원본은 APPROVED 로 남아 둘 다 후보가 된다. 그래서 RETIRED 는 없앤다.
-- - 시나리오는 학급에 묶지 않는 공용 자료다. 재사용은 목표 내용(goal_content_hash)·난이도로만 찾는다.
-- - approved_at 은 재사용 후보 순서에서 "마지막으로 쓰인 때"가 같을 때 최근 승인본을 앞에 두는 데 쓴다.
-- 번호: 다른 PR 이 V25·V26 을 쓰고 있어 V27 로 둔다. 먼저 병합되는 쪽에 맞춰 나중 PR 이 번호를 다시 매긴다(out-of-order 꺼짐).

-- 기존 행을 고치는 동안만 승인본 보호 트리거를 끈다. 같은 트랜잭션에서 다시 켠다.
ALTER TABLE scenario DISABLE TRIGGER trg_scenario_immutable;

-- 원본은 대체되지 않으므로 은퇴했던 승인본도 다시 후보로 돌린다.
UPDATE scenario SET status = 'APPROVED' WHERE status = 'RETIRED';

ALTER TABLE scenario DROP CONSTRAINT scenario_status_check;
ALTER TABLE scenario ADD CONSTRAINT ck_scenario_status
    CHECK (status IN ('DRAFT', 'REVISION_REQUIRED', 'REJECTED', 'APPROVED'));

-- 승인 시각 칸이 없던 때의 승인본은 만든 시각을 승인 시각으로 본다.
ALTER TABLE scenario ADD COLUMN approved_at TIMESTAMPTZ;
UPDATE scenario SET approved_at = created_at WHERE status = 'APPROVED';
ALTER TABLE scenario ADD CONSTRAINT ck_scenario_approved_at
    CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL));

DROP INDEX idx_scenario_reuse;
ALTER TABLE scenario DROP COLUMN class_id;

ALTER TABLE scenario ENABLE TRIGGER trg_scenario_immutable;

-- 상태만 APPROVED 로 바꾸는 기존 스크립트·테스트도 그대로 동작하게, 승인 시각이 비어 있으면 DB 가 채운다.
-- 트리거는 이름 순서로 돌아 보호 트리거(trg_scenario_immutable)보다 먼저 실행된다. 초안에서 승인으로 바뀌는 순간에만 채워지므로
-- 이미 판정된 행을 바꾸지 않는다.
CREATE FUNCTION fill_scenario_approved_at() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND NEW.approved_at IS NULL THEN
        NEW.approved_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_approved_at
    BEFORE INSERT OR UPDATE ON scenario
    FOR EACH ROW EXECUTE FUNCTION fill_scenario_approved_at();

CREATE INDEX idx_scenario_reuse
    ON scenario (goal_content_hash, scenario_level)
    WHERE status = 'APPROVED';

-- 재사용 후보 순서는 그 시나리오가 붙은 활동의 가장 늦은 배정 시각으로 정한다.
CREATE INDEX idx_activity_scenario_assigned
    ON activity (scenario_id, assigned_at)
    WHERE scenario_id IS NOT NULL;

-- 판정이 끝난 행(승인·고쳐 와·반려)은 내용도 상태도 바꾸지 않는다. 판 사이의 연결은 source_scenario_id 로 남는다.
-- 고칠 수 있는 것은 초안(DRAFT)뿐이고, 초안의 상태를 판정으로 바꾸는 것은 허용한다.
CREATE OR REPLACE FUNCTION prevent_approved_scenario_edit() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN
        RAISE EXCEPTION 'Reviewed scenario versions are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 작은 목표와 첫 질문 목표 연결도 초안일 때만 바꿀 수 있다.
-- 시나리오 행을 지우며 함께 지워질 때는 부모 행을 찾지 못해(NULL) 막지 않는다.
CREATE OR REPLACE FUNCTION prevent_approved_micro_goal_edit() RETURNS trigger AS $$
DECLARE
    parent_status TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        SELECT status INTO parent_status FROM scenario
            WHERE scenario_id = OLD.scenario_id FOR SHARE;
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Reviewed micro goals are immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT status INTO parent_status FROM scenario
            WHERE scenario_id = NEW.scenario_id FOR SHARE;
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Reviewed micro goals are immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;
