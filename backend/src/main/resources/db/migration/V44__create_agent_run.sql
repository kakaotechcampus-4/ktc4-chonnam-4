-- 에이전트 워크플로우 실행 기록(운영 대시보드 /ops/agent-runs).
-- 역할극 한 턴·학습 리포트 생성·시나리오 생성 한 번이 agent_run 한 줄이고,
-- 그 안에서 단계가 낸 판정과 그 때문에 이어진 다음 단계가 agent_run_step 이다.
--
-- 담는 값은 고정 코드·횟수·시간·토큰 수뿐이다. 발화·프롬프트·모델 출력 글과
-- 아동·세션·활동 식별자는 담지 않는다. 그래서 아동·학급 삭제와 연결할 열도 없다.
-- 보관 기간이 지난 줄은 서버가 주기적으로 지운다(neuringo.agent-runs.retention).
CREATE TABLE agent_run (
    run_id          UUID        PRIMARY KEY,
    workflow        VARCHAR(32) NOT NULL,
    path            VARCHAR(16) NOT NULL,
    result          VARCHAR(64) NOT NULL,
    ended_by        VARCHAR(64),
    reason          VARCHAR(64),
    input_type      VARCHAR(8),
    http_status     SMALLINT,
    replayed        BOOLEAN     NOT NULL DEFAULT FALSE,
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     INTEGER     NOT NULL,
    ai_calls        SMALLINT    NOT NULL,
    repeated_calls  SMALLINT    NOT NULL,
    input_tokens    INTEGER     NOT NULL,
    output_tokens   INTEGER     NOT NULL,
    truncated       BOOLEAN     NOT NULL DEFAULT FALSE,
    stage_ms        JSONB       NOT NULL,
    spans           JSONB       NOT NULL,
    CONSTRAINT ck_agent_run_workflow
        CHECK (workflow IN ('ROLEPLAY_TURN', 'LEARNING_REPORT', 'SCENARIO_GENERATION')),
    -- NORMAL 한 번에 통과, RETRIED 거절·실패 뒤 다시 돌아 정상 결과,
    -- GUIDED 입력 문제·안전 판정 등 설계된 안내로 끝남, FAILED 호출 실패·한도·시간 초과·서버 오류.
    CONSTRAINT ck_agent_run_path
        CHECK (path IN ('NORMAL', 'RETRIED', 'GUIDED', 'FAILED')),
    CONSTRAINT ck_agent_run_counts
        CHECK (duration_ms >= 0 AND ai_calls >= 0 AND repeated_calls >= 0
               AND input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT ck_agent_run_stage_ms CHECK (jsonb_typeof(stage_ms) = 'object'),
    CONSTRAINT ck_agent_run_spans CHECK (jsonb_typeof(spans) = 'array')
);

CREATE INDEX ix_agent_run_started_at ON agent_run (started_at DESC);

CREATE TABLE agent_run_step (
    run_id          UUID        NOT NULL REFERENCES agent_run (run_id) ON DELETE CASCADE,
    seq             SMALLINT    NOT NULL,
    -- CALL AI·음성 호출, CHECK 코드가 내린 판정, RESULT 최종 결과.
    kind            VARCHAR(8)  NOT NULL,
    step            VARCHAR(64) NOT NULL,
    attempt         SMALLINT    NOT NULL,
    status          VARCHAR(8)  NOT NULL,
    verdict         VARCHAR(64),
    codes           JSONB       NOT NULL,
    -- declared_next 는 판정이 지목한 다음 단계, led_to 는 실제로 이어진 다음 단계다.
    declared_next   VARCHAR(64),
    led_to          VARCHAR(64),
    at_ms           INTEGER     NOT NULL,
    duration_ms     INTEGER     NOT NULL,
    model           VARCHAR(64),
    prompt_version  VARCHAR(64),
    finish_reason   VARCHAR(64),
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    PRIMARY KEY (run_id, seq),
    CONSTRAINT ck_agent_run_step_kind CHECK (kind IN ('CALL', 'CHECK', 'RESULT')),
    CONSTRAINT ck_agent_run_step_status CHECK (status IN ('PASSED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_agent_run_step_codes CHECK (jsonb_typeof(codes) = 'array')
);
