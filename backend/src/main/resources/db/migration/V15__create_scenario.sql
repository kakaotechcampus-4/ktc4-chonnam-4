-- 역할극 시나리오. 버전 없이 바꾸면 새 시나리오를 만들고, 고친 판이 승인되면 원본은 RETIRED 가 된다.
-- 학급·강사에 묶지 않는 공용 자료라 class_id·만든 사람 칸이 없다. 승인 기록은 시나리오가 아니라 활동에 남긴다.
-- 팀이 미리 넣는 시나리오(db/seed)는 목표 해시 없이 APPROVED 로 들어가고, 첫 활동은 난이도만 맞춰 그중에서 고른다.
CREATE TABLE scenario (
    scenario_id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    scenario_level VARCHAR(2) NOT NULL CHECK (scenario_level IN ('L1', 'L2', 'L3')),
    goal_content_hash VARCHAR(64),
    source_scenario_id UUID REFERENCES scenario (scenario_id),
    title VARCHAR(200) NOT NULL,
    summary TEXT,
    ai_role TEXT,
    characters JSONB NOT NULL DEFAULT '[]',
    scenario_facts JSONB NOT NULL,
    prohibited_inferences JSONB NOT NULL DEFAULT '[]',
    -- 시작 질문. 지원 단계(S0·S1·S2)별 문장 하나씩이다.
    opening_prompts JSONB NOT NULL DEFAULT '{}',
    origin VARCHAR(20) NOT NULL CHECK (origin IN ('PREPARED', 'AI_GENERATED', 'INSTRUCTOR_EDITED')),
    prompt_version VARCHAR(50),
    policy_version VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_scenario_level_status ON scenario (scenario_level, status);

-- 작은 목표. order_no 는 보여 주는 순서일 뿐 통과 순서가 아니다(한 대답으로 여러 목표가 함께 채워질 수 있다).
CREATE TABLE scenario_micro_goal (
    micro_goal_id UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES scenario (scenario_id) ON DELETE CASCADE,
    order_no INTEGER NOT NULL CHECK (order_no > 0),
    description TEXT NOT NULL,
    required_evidence JSONB NOT NULL,
    achievement_example TEXT,
    UNIQUE (scenario_id, order_no)
);

-- 같은 아동에게 같은 시나리오로 끝나지 않은 활동은 하나만 둔다. 앱 검사가 동시에 통과해도 여기서 막힌다.
-- activity.scenario_id 에는 외래 키를 걸지 않는다(goal_id 와 같다). 역할극 테스트가 시나리오 표 없이 임의 ID 로 활동을 만든다.
CREATE UNIQUE INDEX uq_activity_child_open_scenario
    ON activity (child_id, scenario_id)
    WHERE scenario_id IS NOT NULL AND status <> 'COMPLETED';
