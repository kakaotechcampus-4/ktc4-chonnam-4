-- 초안은 한 아동의 학습 목표를 위해 만든다(명세 응답 goalId). 승인되면 공용 자료가 되어 목표에서 떨어진다.
-- - 초안과 판정 기록(고쳐 와·반려)은 그 목표에 매여, 아동을 지우면서 목표가 지워질 때 함께 지워진다.
-- - 승인본은 한 아동의 목표에 매이지 않는다. 승인하는 같은 갱신에서 goal_id 를 비운다.
-- - 목표 하나에 초안은 하나만 둔다.
ALTER TABLE scenario
    ADD COLUMN goal_id UUID REFERENCES learning_goal (goal_id) ON DELETE CASCADE;
ALTER TABLE scenario ADD CONSTRAINT ck_scenario_approved_without_goal
    CHECK (status <> 'APPROVED' OR goal_id IS NULL);
CREATE INDEX idx_scenario_goal ON scenario (goal_id) WHERE goal_id IS NOT NULL;
CREATE UNIQUE INDEX uq_scenario_draft_per_goal ON scenario (goal_id) WHERE status = 'DRAFT';

-- 고쳐 온 원본이 지워지면(아동 삭제로 목표에 매인 판이 지워질 때) 새 판의 출처만 비운다.
ALTER TABLE scenario DROP CONSTRAINT scenario_source_scenario_id_fkey;
ALTER TABLE scenario ADD CONSTRAINT fk_scenario_source
    FOREIGN KEY (source_scenario_id) REFERENCES scenario (scenario_id) ON DELETE SET NULL;

-- 판정이 끝난 행은 그대로 두되, 출처를 비우는 변경만 허용한다(위 ON DELETE SET NULL).
CREATE OR REPLACE FUNCTION prevent_approved_scenario_edit() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        (to_jsonb(NEW) - 'source_scenario_id') IS DISTINCT FROM (to_jsonb(OLD) - 'source_scenario_id')
        OR (NEW.source_scenario_id IS DISTINCT FROM OLD.source_scenario_id
            AND NEW.source_scenario_id IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'Reviewed scenario versions are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
