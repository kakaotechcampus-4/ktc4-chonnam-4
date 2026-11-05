CREATE TABLE scenario_presentation_stat (
    instructor_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    scenario_id UUID NOT NULL REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    last_presented_at TIMESTAMPTZ NOT NULL,
    presentation_count BIGINT NOT NULL CHECK (presentation_count > 0),
    PRIMARY KEY (instructor_id, scenario_id)
);

CREATE TABLE scenario_presentation_daily (
    instructor_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    scenario_id UUID NOT NULL REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    presentation_day DATE NOT NULL,
    presentation_count BIGINT NOT NULL CHECK (presentation_count > 0),
    PRIMARY KEY (instructor_id, scenario_id, presentation_day)
);

CREATE TABLE scenario_review (
    review_id UUID PRIMARY KEY,
    activity_id UUID NOT NULL UNIQUE REFERENCES activity (activity_id) ON DELETE CASCADE,
    instructor_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    input_fingerprint VARCHAR(64) NOT NULL,
    current_position INTEGER CHECK (current_position > 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scenario_review_candidate (
    review_id UUID NOT NULL REFERENCES scenario_review (review_id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position > 0),
    -- 원본이 삭제·폐기되어도 고정 목록은 보존하고 제시 직전에 자격을 다시 확인한다.
    scenario_id UUID NOT NULL,
    scenario_version INTEGER NOT NULL CHECK (scenario_version > 0),
    PRIMARY KEY (review_id, position),
    UNIQUE (review_id, scenario_id)
);

ALTER TABLE scenario_review ADD CONSTRAINT fk_scenario_review_current_position
    FOREIGN KEY (review_id, current_position)
    REFERENCES scenario_review_candidate (review_id, position)
    DEFERRABLE INITIALLY DEFERRED;

-- 검토 종료 후 목록을 삭제해도 과거 시작 요청이 검토를 다시 만들지 못하도록 유지한다.
CREATE TABLE scenario_review_request (
    activity_id UUID NOT NULL REFERENCES activity (activity_id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    review_id UUID NOT NULL,
    request_kind VARCHAR(30) NOT NULL,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('ACTIVE', 'CANCELLED', 'CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, request_id)
);
