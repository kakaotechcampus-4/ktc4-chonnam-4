-- 로컬 끝까지 돌려 보기용 승인 시나리오. 시나리오를 저장하는 서버 API 가 아직 없어서(contracts/api/v1/scenarios 는 설계) 직접 넣는다.
-- 한 번 실행하면 같은 학급·같은 목표 내용(content_hash)에 L1·L2·L3 승인본이 하나씩 생긴다. 역할극은 이 아동이 아직 쓰지 않은
-- 승인본을 고르므로, 같은 목표로 역할극을 두 번 하려면 tag 를 바꿔 한 번 더 실행한다.
--
--   psql -v class_id=<학급 UUID> -v goal_hash=<learning_goal.content_hash> -v tag=<구분 글자> -f seed-approved-scenarios.sql
--   (scripts/local/seed-scenarios.ps1 가 활동 ID 로 두 값을 찾아 부른다)
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE seeded (scenario_id uuid PRIMARY KEY, level text NOT NULL) ON COMMIT DROP;
INSERT INTO seeded SELECT gen_random_uuid(), lv FROM unnest(ARRAY['L1', 'L2', 'L3']) AS lv;

INSERT INTO scenario (scenario_id, status, scenario_level, goal_content_hash, title, summary, ai_role, characters,
    scenario_facts, prohibited_inferences, opening_prompts, origin, prompt_version, policy_version, created_at,
    class_id, scenario_version)
SELECT s.scenario_id, 'DRAFT', s.level, :'goal_hash',
       '놀이터에서 넘어진 친구 ' || s.level || ' (' || :'tag' || ')',
       '쉬는 시간에 놀이터에서 친구 민준이가 뛰다가 넘어졌다. 아동은 민준이의 마음을 알아보고 위로하는 말을 연습한다.',
       '넘어져서 무릎을 다친 친구 민준',
       '[{"id": "C1", "role": "넘어진 친구 민준"}]'::jsonb,
       '["민준이가 놀이터에서 뛰다가 넘어졌다", "민준이 무릎에서 피가 조금 난다", "민준이가 무릎을 잡고 울먹인다"]'::jsonb,
       '["민준이가 일부러 넘어졌다고 단정하지 않는다", "민준이의 성격을 판단하지 않는다"]'::jsonb,
       '{"S0": "민준이가 놀이터에서 넘어져서 무릎을 잡고 있어. 민준이는 지금 어떤 마음일까?",
         "S1": "민준이 무릎에서 피가 나고 눈물이 글썽글썽해. 민준이는 지금 어떤 마음일까?",
         "S2": "민준이가 넘어져서 울먹이고 있어. 민준이는 지금 속상할까, 신날까?"}'::jsonb,
       'PREPARED', 'local-seed/v1', 'local-seed/v1', now(), :'class_id'::uuid, 1
FROM seeded s;

-- L1·L2 는 작은 목표 3개, L3 은 4개(ScenarioOutputParser 의 크기 규칙과 같다). 기준 문장은 목표마다 하나다.
INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
SELECT gen_random_uuid(), s.scenario_id, g.order_no, g.description, g.evidence::jsonb, g.example
FROM seeded s
CROSS JOIN (VALUES
    (1, '감정 이름 말하기', '["민준이가 아프거나 속상하다는 감정"]', '민준이가 아프고 속상할 것 같아'),
    (2, '감정의 이유 말하기', '["넘어져서 무릎을 다쳤다는 이유"]', '넘어져서 무릎이 아파서 속상해'),
    (3, '위로하는 말 하기', '["괜찮은지 묻거나 걱정하는 말"]', '민준아 괜찮아? 많이 아프지?'),
    (4, '도움 행동 말하기', '["선생님께 알리거나 일으켜 주는 행동"]', '내가 선생님한테 같이 가 줄게')
) AS g(order_no, description, evidence, example)
WHERE g.order_no <= CASE s.level WHEN 'L3' THEN 4 ELSE 3 END;

-- 어느 시작 지원으로 시작해도 첫 질문의 초점은 첫 작은 목표다.
INSERT INTO scenario_opening_focus (scenario_id, support_level, micro_goal_id)
SELECT s.scenario_id, support, m.micro_goal_id
FROM seeded s
JOIN scenario_micro_goal m ON m.scenario_id = s.scenario_id AND m.order_no = 1
CROSS JOIN unnest(ARRAY['S0', 'S1', 'S2']) AS support;

UPDATE scenario SET status = 'APPROVED' WHERE scenario_id IN (SELECT scenario_id FROM seeded);

SELECT level, scenario_id FROM seeded ORDER BY level;
COMMIT;
