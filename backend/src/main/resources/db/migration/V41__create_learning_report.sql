-- 역할극이 끝난 활동 하나에 학습 리포트가 하나 생긴다(03 VS-014). 지표와 목표별 결과는 LLM 과 상관없이 완료 직후 서버가 계산해 남기고,
-- AI 요약은 나중에 채운다. 강사 화면은 COMPLETED 만 보여 준다. 아동 발화 원문은 어느 칸에도 넣지 않는다.
CREATE TABLE learning_report (
    report_id UUID PRIMARY KEY,
    record_id UUID NOT NULL REFERENCES learning_record (record_id),
    activity_id UUID NOT NULL REFERENCES activity (activity_id),
    child_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    -- 강사 화면에 보일 서버 계산값(퀴즈·목표별 시작·최종 지원·기준별 관찰·기술 제외·난이도 근거)
    snapshot JSONB NOT NULL,
    -- 생성 루프 입력. 근거 ID 와 관찰 문장만 담고 아동 이름·발화·요청 ID 는 담지 않는다.
    llm_input JSONB NOT NULL,
    summary TEXT,
    -- AI 초안의 관찰·효과적인 지원·어려운 지점 항목과 근거 ID
    items JSONB,
    failure_reason VARCHAR(40),
    prompt_version VARCHAR(80) NOT NULL,
    -- 바퀴마다 결과·실패 코드·평가 점수. 초안 문장은 남기지 않는다.
    rounds JSONB NOT NULL DEFAULT '[]'::jsonb,
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_learning_report_activity UNIQUE (activity_id),
    CONSTRAINT uq_learning_report_record UNIQUE (record_id),
    CONSTRAINT ck_learning_report_completed CHECK (
        status <> 'COMPLETED' OR (summary IS NOT NULL AND items IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_learning_report_failed CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

CREATE INDEX ix_learning_report_child ON learning_report (child_id, created_at DESC);
CREATE INDEX ix_learning_report_pending ON learning_report (updated_at) WHERE status = 'PENDING';

-- 리포트와 같은 시점에 서버 규칙(RECOMMENDATION_V1)으로 만든 다음 수업 제안. 최대 3개이고 강사가 고르기 전에는 아무것도 배정하지 않는다.
CREATE TABLE recommendation (
    recommendation_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES learning_report (report_id),
    record_id UUID NOT NULL REFERENCES learning_record (record_id),
    activity_id UUID NOT NULL REFERENCES activity (activity_id),
    child_id UUID NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('REINFORCE', 'REPEAT', 'APPLY', 'EXTEND')),
    display_order INT NOT NULL CHECK (display_order BETWEEN 1 AND 3),
    reason_code VARCHAR(40) NOT NULL,
    reason TEXT NOT NULL,
    basis_goal_title TEXT,
    evidence JSONB NOT NULL,
    evidence_sufficiency VARCHAR(16) NOT NULL
        CHECK (evidence_sufficiency IN ('SUFFICIENT', 'LIMITED', 'INSUFFICIENT')),
    suggested_goal JSONB NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('PROPOSED', 'ACCEPTED', 'MODIFIED_ACCEPTED', 'DEFERRED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    CONSTRAINT uq_recommendation_order UNIQUE (record_id, display_order)
);

CREATE INDEX ix_recommendation_child ON recommendation (child_id, created_at DESC);

-- 강사의 결정 기록. 같은 요청 키를 다시 보내면 이 행으로 앞 결과를 돌려준다.
CREATE TABLE recommendation_decision (
    decision_id UUID PRIMARY KEY,
    recommendation_id UUID NOT NULL REFERENCES recommendation (recommendation_id),
    idempotency_key UUID NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('ACCEPT', 'MODIFIED_ACCEPT', 'DEFER', 'REJECT')),
    goal JSONB,
    rejection_reason VARCHAR(40),
    decided_by UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recommendation_decision_key UNIQUE (idempotency_key)
);

CREATE INDEX ix_recommendation_decision_target ON recommendation_decision (recommendation_id, decided_at);
