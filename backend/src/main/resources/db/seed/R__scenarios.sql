-- 팀이 미리 넣어 두는 역할극 시나리오. 시나리오를 넣고 고치는 곳은 이 파일 하나다(스키마 변경은 db/migration).
-- Flyway 반복 실행 파일이라 내용이 바뀌면 모든 V 마이그레이션 뒤에 다시 실행된다. 몇 번을 돌려도 결과가 같게 ID 로 덮어쓴다.
--
-- 쓰이는 곳
-- - 아이가 최초 결합 활동의 퀴즈를 마치면 서버가 난이도(L1~L3)를 정하고, 그 난이도의 APPROVED 시나리오 중
--   그 아이가 배정받은 적 없는 것을 하나 골라 활동에 붙인다. 목표는 보지 않는다. 강사 승인 단계는 없다.
--
-- 고칠 때 지킬 것
-- - 이미 배정된 시나리오의 내용은 고치지 않는다. 활동이 이 행을 가리키고 있어, 고치면 지난 역할극이 본 내용도 바뀐다.
--   바꾸려면 새 scenario_id 로 더하고, 예전 것은 status 를 'RETIRED' 로 내린다(새 배정에서 빠지고 기록은 남는다).
-- - 행은 지우지 않는다.
-- - goal_content_hash 는 비워 둔다. 목표 맞춤 시나리오(AI 생성)와 구분하는 값이다.
-- - 실제 아동 이름·정보는 넣지 않는다. 등장인물은 지어낸 이름만 쓴다.
--
-- 아래 세 개는 팀 검토 전 예시다. 내용을 쓰고 검토하는 일은 시나리오 담당이 한다.
INSERT INTO scenario (
    scenario_id, status, scenario_level, goal_content_hash, source_scenario_id,
    title, summary, ai_role, characters, scenario_facts, prohibited_inferences, opening_prompts,
    origin, prompt_version, policy_version, created_at
) VALUES
(
    '5ce00000-0000-4000-8000-000000000001', 'APPROVED', 'L1', NULL, NULL,
    '친구의 그림이 찢어졌어요', '친구 다온이의 그림이 찢어져 다온이가 속상해한다.', '친구 다온',
    '[{"name": "다온", "relation": "같은 반 친구"}]',
    '["다온이가 그린 그림이 찢어졌다.", "다온이는 고개를 숙이고 있다."]',
    '["다온이가 그림을 일부러 찢었다고 말하지 않는다."]',
    '{"S0": "다온이가 왜 고개를 숙이고 있을까?", "S1": "다온이 그림이 찢어졌어. 다온이 기분이 어떨까?", "S2": "다온이가 속상해 보여. \"괜찮아?\" 하고 물어볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
),
(
    '5ce00000-0000-4000-8000-000000000002', 'APPROVED', 'L2', NULL, NULL,
    '놀이 순서를 기다려요', '미끄럼틀 앞에서 친구 하람이와 순서를 정해야 한다.', '친구 하람',
    '[{"name": "하람", "relation": "같은 반 친구"}]',
    '["미끄럼틀 앞에 줄이 있다.", "하람이가 먼저 줄을 섰다."]',
    '["하람이가 새치기를 했다고 말하지 않는다."]',
    '{"S0": "하람이랑 미끄럼틀 앞에 있어. 뭐라고 말해 볼까?", "S1": "하람이가 먼저 줄을 섰어. 기다리는 동안 기분이 어때?", "S2": "\"하람아, 다음은 내 차례야\" 하고 말해 볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
),
(
    '5ce00000-0000-4000-8000-000000000003', 'APPROVED', 'L3', NULL, NULL,
    '친구가 넘어졌어요', '운동장에서 친구 서진이가 넘어져 무릎을 다쳤다.', '친구 서진',
    '[{"name": "서진", "relation": "같은 반 친구"}]',
    '["서진이가 달리다 넘어졌다.", "서진이 무릎에 상처가 났다.", "선생님은 교실에 있다."]',
    '["서진이가 장난치다 넘어졌다고 말하지 않는다."]',
    '{"S0": "서진이가 넘어졌어. 어떻게 하면 좋을까?", "S1": "서진이 무릎이 아파 보여. 서진이한테 뭐라고 말해 줄까?", "S2": "\"많이 아파? 선생님께 같이 갈까?\" 하고 말해 볼까?"}',
    'PREPARED', NULL, NULL, '2026-10-27T00:00:00Z'
)
ON CONFLICT (scenario_id) DO UPDATE SET
    status = EXCLUDED.status,
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
    policy_version = EXCLUDED.policy_version;

INSERT INTO scenario_micro_goal (
    micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example
) VALUES
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
)
ON CONFLICT (micro_goal_id) DO UPDATE SET
    scenario_id = EXCLUDED.scenario_id,
    order_no = EXCLUDED.order_no,
    description = EXCLUDED.description,
    required_evidence = EXCLUDED.required_evidence,
    achievement_example = EXCLUDED.achievement_example;
