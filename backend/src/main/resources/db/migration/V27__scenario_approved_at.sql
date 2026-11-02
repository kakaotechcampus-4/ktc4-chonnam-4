-- 승인 시각. 역할극 단독 배정의 후보 목록이 "마지막으로 쓰인 지 오래된 것 → 최근 승인본" 순으로 보여 주는 데 쓴다.
-- 승인 API 가 아직 없어 승인은 status 를 APPROVED 로 바꾸는 것뿐이다. 그래서 칸은 트리거가 채운다.
ALTER TABLE scenario ADD COLUMN approved_at TIMESTAMPTZ;

-- 이미 승인된 행은 승인 시각을 모르므로 만든 시각으로 채운다. 승인본 수정 금지 트리거를 잠시 끈다.
ALTER TABLE scenario DISABLE TRIGGER trg_scenario_immutable;
UPDATE scenario SET approved_at = created_at WHERE status IN ('APPROVED', 'RETIRED');
ALTER TABLE scenario ENABLE TRIGGER trg_scenario_immutable;

-- APPROVED 로 들어오거나 바뀌는 순간에만 채운다. 승인 뒤에는 수정 금지 트리거가 다른 칸 변경을 막는다.
CREATE FUNCTION stamp_scenario_approved_at() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND NEW.approved_at IS NULL
        AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'APPROVED') THEN
        NEW.approved_at := now();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scenario_approved_at
    BEFORE INSERT OR UPDATE ON scenario
    FOR EACH ROW EXECUTE FUNCTION stamp_scenario_approved_at();
