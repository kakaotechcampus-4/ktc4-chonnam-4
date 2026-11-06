-- 역할극을 마칠 때마다 다음 역할극 난이도를 정한다(03 VS-016). 결정마다 점수율·연속 상향 후보 수·정책 버전을 남겨
-- 같은 입력과 같은 버전이면 같은 결과를 다시 낼 수 있게 한다. 다음 결정은 이 아동의 가장 최근 결정의 연속 후보 수를 입력으로 쓴다.
CREATE TABLE follow_up_difficulty_decision (
    decision_id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activity (activity_id),
    child_id UUID NOT NULL,
    previous_level VARCHAR(2) NOT NULL CHECK (previous_level IN ('L1', 'L2', 'L3')),
    next_level VARCHAR(2) NOT NULL CHECK (next_level IN ('L1', 'L2', 'L3')),
    next_support_level VARCHAR(2) NOT NULL CHECK (next_support_level IN ('S0', 'S1', 'S2')),
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('LOWER', 'KEEP', 'RAISE')),
    reason VARCHAR(40) NOT NULL,
    score_numerator INT NOT NULL CHECK (score_numerator >= 0),
    score_denominator INT NOT NULL CHECK (score_denominator >= 0),
    consecutive_upgrade_candidates INT NOT NULL CHECK (consecutive_upgrade_candidates >= 0),
    policy_version VARCHAR(80) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_follow_up_difficulty_activity UNIQUE (activity_id),
    CONSTRAINT ck_follow_up_difficulty_score CHECK (score_numerator <= score_denominator)
);

CREATE INDEX ix_follow_up_difficulty_child ON follow_up_difficulty_decision (child_id, decided_at DESC);

-- 역할극 단독 활동을 배정할 때 그 시점의 다음 난이도를 고정한다. 배정 뒤 다른 역할극이 끝나 결정이 바뀌어도
-- 이미 배정한 활동의 레벨·시작 지원은 바뀌지 않는다.
CREATE TABLE roleplay_activity_difficulty (
    activity_id UUID PRIMARY KEY REFERENCES activity (activity_id),
    scenario_level VARCHAR(2) NOT NULL CHECK (scenario_level IN ('L1', 'L2', 'L3')),
    initial_support_level VARCHAR(2) NOT NULL CHECK (initial_support_level IN ('S0', 'S1', 'S2')),
    source_decision_id UUID NOT NULL REFERENCES follow_up_difficulty_decision (decision_id),
    pinned_at TIMESTAMPTZ NOT NULL
);
