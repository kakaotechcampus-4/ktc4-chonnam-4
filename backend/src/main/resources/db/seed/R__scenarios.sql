-- 팀이 미리 넣어 두는 역할극 시나리오. 시나리오를 넣고 고치는 곳은 이 파일 하나다(스키마 변경은 db/migration).
-- Flyway 반복 실행 파일이라 내용이 바뀌면 모든 V 마이그레이션 뒤에 다시 실행된다. 몇 번을 돌려도 결과가 같다.
--
-- 쓰이는 곳
-- - 아이가 최초 결합 활동의 퀴즈를 마치면 서버가 난이도(L1~L3)를 정하고, 그 난이도의 APPROVED 시나리오 중
--   그 아이가 배정받은 적 없는 것을 하나 골라 활동에 붙인다. 목표는 보지 않는다. 강사 승인 단계는 없다.
--
-- 고칠 때 지킬 것
-- - 이 파일은 세 단계로 돈다. ① 시나리오 본문을 늘 DRAFT 로 넣거나 고친다 ② 작은 목표를 넣거나 고친다 ③ 파일 끝 "승인하기"에 적은 ID 를
--   APPROVED 로 올린다. 승인을 맨 끝에 두어야 ①②가 DRAFT 상태에서 끝까지 저장된다. 상태는 INSERT 에 적지 않는다.
-- - 팀 검토를 마치면 "승인하기"에 그 ID 를 더한다. 작은 목표가 하나 이상 있는 DRAFT 만 올라가고, 그때부터 아이에게 붙는다.
-- - DRAFT 인 행만 이 파일로 내용을 고칠 수 있다. APPROVED·RETIRED 행은 아래 INSERT 를 고쳐도 본문도 작은 목표도 바뀌지 않는다.
--   승인본은 활동이 가리키고 있어, 고치면 지난 역할극이 본 내용도 바뀌기 때문이다.
-- - 승인본을 바꾸려면 새 scenario_id 로 DRAFT 를 더해 검토·승인하고, 예전 것은 파일 끝의 UPDATE 로 'RETIRED' 로 내린다
--   (새 배정에서 빠지고 기록은 남는다).
-- - 행은 지우지 않는다.
-- - goal_content_hash 는 비워 둔다. 목표 맞춤 시나리오(AI 생성)와 구분하는 값이다.
-- - 실제 아동 이름·정보는 넣지 않는다. 등장인물은 지어낸 이름만 쓴다.
--
-- 아래 세 개는 팀 검토 전 예시라 DRAFT 다. 아이에게 붙지 않는다. 내용을 쓰고 검토하는 일은 시나리오 담당이 한다.
INSERT INTO scenario (
    scenario_id, status, scenario_level, goal_content_hash, source_scenario_id,
    title, summary, ai_role, characters, scenario_facts, prohibited_inferences, opening_prompts,
    origin, prompt_version, policy_version, created_at
)
SELECT v.scenario_id::uuid, 'DRAFT', v.scenario_level, v.goal_content_hash, v.source_scenario_id::uuid,
    v.title, v.summary, v.ai_role, v.characters::jsonb, v.scenario_facts::jsonb, v.prohibited_inferences::jsonb,
    v.opening_prompts::jsonb, v.origin, v.prompt_version, v.policy_version, v.created_at::timestamptz
FROM (VALUES
(
    '5ce00000-0000-4000-8000-000000000001', 'L1', NULL, NULL,
    '친구의 그림이 찢어졌어요', '친구 다온이의 그림이 찢어져 다온이가 속상해한다.', '친구 다온',
    '[{"name": "다온", "relation": "같은 반 친구"}]',
    '["다온이가 그린 그림이 찢어졌다.", "다온이는 고개를 숙이고 있다."]',
    '["다온이가 그림을 일부러 찢었다고 말하지 않는다."]',
    '{"S0": "다온이가 왜 고개를 숙이고 있을까?", "S1": "다온이 그림이 찢어졌어. 다온이 기분이 어떨까?", "S2": "다온이가 속상해 보여. \"괜찮아?\" 하고 물어볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
),
(
    '5ce00000-0000-4000-8000-000000000002', 'L2', NULL, NULL,
    '놀이 순서를 기다려요', '미끄럼틀 앞에서 친구 하람이와 순서를 정해야 한다.', '친구 하람',
    '[{"name": "하람", "relation": "같은 반 친구"}]',
    '["미끄럼틀 앞에 줄이 있다.", "하람이가 먼저 줄을 섰다."]',
    '["하람이가 새치기를 했다고 말하지 않는다."]',
    '{"S0": "하람이랑 미끄럼틀 앞에 있어. 뭐라고 말해 볼까?", "S1": "하람이가 먼저 줄을 섰어. 기다리는 동안 기분이 어때?", "S2": "\"하람아, 다음은 내 차례야\" 하고 말해 볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
),
(
    '5ce00000-0000-4000-8000-000000000003', 'L3', NULL, NULL,
    '친구가 넘어졌어요', '운동장에서 친구 서진이가 넘어져 무릎을 다쳤다.', '친구 서진',
    '[{"name": "서진", "relation": "같은 반 친구"}]',
    '["서진이가 달리다 넘어졌다.", "서진이 무릎에 상처가 났다.", "선생님은 교실에 있다."]',
    '["서진이가 장난치다 넘어졌다고 말하지 않는다."]',
    '{"S0": "서진이가 넘어졌어. 어떻게 하면 좋을까?", "S1": "서진이 무릎이 아파 보여. 서진이한테 뭐라고 말해 줄까?", "S2": "\"많이 아파? 선생님께 같이 갈까?\" 하고 말해 볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
)) AS v (
    scenario_id, scenario_level, goal_content_hash, source_scenario_id,
    title, summary, ai_role, characters, scenario_facts, prohibited_inferences, opening_prompts,
    origin, prompt_version, policy_version, created_at
)
ON CONFLICT (scenario_id) DO UPDATE SET
    scenario_level = EXCLUDED.scenario_level,
    goal_content_hash = EXCLUDED.goal_content_hash,
    source_scenario_id = EXCLUDED.source_scenario_id,
    title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    ai_role = EXCLUDED.ai_role,
    characters = EXCLUDED.characters,
    scenario_facts = EXCLUDED.scenario_facts,
    prohibited_inferences = EXCLUDED.prohibited_inferences,
    opening_prompts = EXCLUDED.opening_prompts,
    origin = EXCLUDED.origin,
    prompt_version = EXCLUDED.prompt_version,
    policy_version = EXCLUDED.policy_version
WHERE scenario.status = 'DRAFT';

-- 작은 목표도 DRAFT 시나리오에만 넣거나 고친다. 승인본에 목표가 새로 끼어들지 않게 부모 상태를 보고 거른다.
INSERT INTO scenario_micro_goal (
    micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example
)
SELECT v.micro_goal_id::uuid, v.scenario_id::uuid, v.order_no, v.description, v.required_evidence::jsonb, v.achievement_example
FROM (VALUES
(
    '5ce00000-0000-4000-8000-000000000101', '5ce00000-0000-4000-8000-000000000001', 1,
    '다온이의 기분을 말로 짐작한다', '["다온이의 감정을 나타내는 낱말을 말한다"]', '"다온이 슬퍼 보여."'
),
(
    '5ce00000-0000-4000-8000-000000000102', '5ce00000-0000-4000-8000-000000000001', 2,
    '다온이에게 위로하는 말을 한다', '["위로나 걱정을 담은 말을 다온이에게 한다"]', '"괜찮아? 다시 그리자."'
),
(
    '5ce00000-0000-4000-8000-000000000201', '5ce00000-0000-4000-8000-000000000002', 1,
    '기다리는 기분을 말로 표현한다', '["자기 감정을 나타내는 낱말을 말한다"]', '"빨리 타고 싶어서 조금 답답해."'
),
(
    '5ce00000-0000-4000-8000-000000000202', '5ce00000-0000-4000-8000-000000000002', 2,
    '자기 차례를 말로 알린다', '["순서를 묻거나 자기 차례를 말한다"]', '"하람아, 다음은 내 차례야."'
),
(
    '5ce00000-0000-4000-8000-000000000301', '5ce00000-0000-4000-8000-000000000003', 1,
    '서진이의 상태를 묻는다', '["서진이가 괜찮은지 묻는다"]', '"많이 아파?"'
),
(
    '5ce00000-0000-4000-8000-000000000302', '5ce00000-0000-4000-8000-000000000003', 2,
    '도울 방법을 제안한다', '["함께 할 행동이나 도움을 제안한다"]', '"선생님께 같이 가자."'
)) AS v (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
JOIN scenario s ON s.scenario_id = v.scenario_id::uuid
WHERE s.status = 'DRAFT'
ON CONFLICT (micro_goal_id) DO UPDATE SET
    scenario_id = EXCLUDED.scenario_id,
    order_no = EXCLUDED.order_no,
    description = EXCLUDED.description,
    required_evidence = EXCLUDED.required_evidence,
    achievement_example = EXCLUDED.achievement_example
WHERE (SELECT s.status FROM scenario s WHERE s.scenario_id = scenario_micro_goal.scenario_id) = 'DRAFT';

-- 승인하기. 팀 검토를 마친 시나리오의 ID 를 아래 목록에 더한다. 작은 목표가 하나 이상 있는 DRAFT 만 올라간다.
-- UPDATE scenario s SET status = 'APPROVED'
-- WHERE s.status = 'DRAFT'
--   AND s.scenario_id IN ('...')
--   AND EXISTS (SELECT 1 FROM scenario_micro_goal g WHERE g.scenario_id = s.scenario_id);

-- 승인본 내리기. 대체된 승인본의 ID 를 아래에 더한다. 이미 붙은 활동은 계속 그 시나리오를 가리킨다.
-- UPDATE scenario SET status = 'RETIRED' WHERE status = 'APPROVED' AND scenario_id IN ('...');
