-- 취소 전에 시작한 후보 조회가 뒤늦게 검토를 재생성하지 못하도록 비교한다.
ALTER TABLE activity ADD COLUMN scenario_review_epoch BIGINT NOT NULL DEFAULT 0
    CHECK (scenario_review_epoch >= 0);

ALTER TABLE scenario_review_request
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_scenario_review_cancel_receipt CHECK (
        request_kind <> 'CANCEL' OR (
            outcome = 'CANCELLED' AND request_fingerprint IS NOT NULL AND closed_at IS NOT NULL
            AND expected_revision IS NULL AND result_snapshot IS NULL
        )
    );

CREATE INDEX idx_scenario_review_request_active
    ON scenario_review_request (activity_id, review_id) WHERE outcome = 'ACTIVE';
