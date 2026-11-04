ALTER TABLE scenario_review_candidate ADD COLUMN rejected_at TIMESTAMPTZ;

-- 활성 검토에서는 NEXT의 최초 응답을 보관한다. 검토 종료 시 두 필드를 비운다.
ALTER TABLE scenario_review_request
    ADD COLUMN expected_revision BIGINT CHECK (expected_revision > 0),
    ADD COLUMN result_snapshot JSONB CHECK (jsonb_typeof(result_snapshot) = 'object'),
    ADD CONSTRAINT ck_scenario_review_next_result CHECK (
        request_kind <> 'NEXT' OR outcome <> 'ACTIVE'
        OR (expected_revision IS NOT NULL AND result_snapshot IS NOT NULL)
    );
