CREATE TABLE activity (
    activity_id UUID PRIMARY KEY,
    child_id UUID NOT NULL REFERENCES child (child_id),
    goal_id UUID NOT NULL,
    activity_type VARCHAR(30) NOT NULL DEFAULT 'QUIZ_ROLEPLAY',
    scenario_id UUID,
    scenario_source VARCHAR(30),
    initial_support_level VARCHAR(2),
    difficulty_fallback_applied BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'RECOVERY_NEEDED')),
    assigned_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    reward_issued_at TIMESTAMPTZ
);

CREATE INDEX idx_activity_child_status ON activity (child_id, status, assigned_at);

CREATE TABLE quiz_item (
    item_id UUID PRIMARY KEY,
    quiz_type VARCHAR(40) NOT NULL
        CHECK (quiz_type IN ('SELF_EMOTION_SITUATION', 'OTHER_EMOTION_SITUATION', 'OTHER_EMOTION_IMAGE')),
    emotion VARCHAR(20) NOT NULL
        CHECK (emotion IN ('JOY', 'SADNESS', 'ANGER', 'FEAR', 'SURPRISE', 'UPSET', 'PAIN', 'CALM')),
    question_text TEXT NOT NULL,
    image_url TEXT,
    choices_json JSONB NOT NULL,
    correct_answer VARCHAR(200) NOT NULL,
    acceptable_answers_json JSONB NOT NULL,
    hints_json JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'APPROVED')),
    item_version INTEGER NOT NULL CHECK (item_version > 0)
);

CREATE TABLE activity_quiz (
    activity_quiz_id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activity (activity_id),
    item_id UUID NOT NULL REFERENCES quiz_item (item_id),
    item_version INTEGER NOT NULL CHECK (item_version > 0),
    question_order INTEGER NOT NULL CHECK (question_order BETWEEN 1 AND 3),
    UNIQUE (activity_id, question_order),
    UNIQUE (activity_id, item_id)
);

CREATE INDEX idx_activity_quiz_activity ON activity_quiz (activity_id, question_order);

CREATE TABLE quiz_attempt (
    attempt_id UUID PRIMARY KEY,
    activity_quiz_id UUID NOT NULL UNIQUE REFERENCES activity_quiz (activity_quiz_id),
    first_response VARCHAR(200),
    first_response_correct BOOLEAN NOT NULL,
    expression_match_result VARCHAR(20),
    camera_model_version VARCHAR(100),
    technical_failure_reason VARCHAR(40),
    final_response VARCHAR(200),
    resolved_after_hint BOOLEAN NOT NULL DEFAULT FALSE,
    item_correct BOOLEAN NOT NULL DEFAULT FALSE,
    excluded_from_scoring BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'FINALIZED')),
    row_version BIGINT NOT NULL DEFAULT 0,
    responded_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ
);

CREATE TABLE quiz_hint (
    hint_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES quiz_attempt (attempt_id),
    idempotency_key UUID NOT NULL UNIQUE,
    hint_order INTEGER NOT NULL CHECK (hint_order BETWEEN 1 AND 2),
    hint_type VARCHAR(40) NOT NULL,
    hint_text TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    UNIQUE (attempt_id, hint_order)
);

CREATE TABLE quiz_result (
    activity_id UUID PRIMARY KEY REFERENCES activity (activity_id),
    valid_question_count INTEGER NOT NULL CHECK (valid_question_count >= 0),
    correct_question_count INTEGER NOT NULL CHECK (correct_question_count >= 0),
    overall_accuracy NUMERIC(7, 6),
    total_hint_count INTEGER NOT NULL CHECK (total_hint_count >= 0),
    resolved_after_hint_count INTEGER NOT NULL CHECK (resolved_after_hint_count >= 0),
    scenario_level VARCHAR(2),
    initial_support_level VARCHAR(2),
    difficulty_fallback_applied BOOLEAN NOT NULL,
    initial_difficulty_used BOOLEAN NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    snapshotted_at TIMESTAMPTZ NOT NULL
);
