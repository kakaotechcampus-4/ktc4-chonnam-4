ALTER TABLE scenario_review_candidate ADD COLUMN candidate_source VARCHAR(12) NOT NULL DEFAULT 'REUSE'
    CHECK (candidate_source IN ('REUSE', 'GENERATED'));

-- 승인된 원본은 보존하고 검토에만 속한 미승인 생성 초안을 정리한다.
CREATE FUNCTION cleanup_review_generated_drafts() RETURNS trigger AS $$
BEGIN
    DELETE FROM scenario s USING scenario_review_candidate c
    WHERE c.review_id = OLD.review_id AND c.candidate_source = 'GENERATED'
        AND s.scenario_id = c.scenario_id AND s.status = 'DRAFT'
        AND NOT EXISTS (SELECT 1 FROM activity a WHERE a.scenario_id = s.scenario_id)
        AND NOT EXISTS (SELECT 1 FROM scenario v WHERE v.source_scenario_id = s.scenario_id);
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_review_generated_draft_cleanup BEFORE DELETE ON scenario_review
    FOR EACH ROW EXECUTE FUNCTION cleanup_review_generated_drafts();
