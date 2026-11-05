-- 로컬 확인용 승인 시나리오. AI 는 넘어진 친구(FRIEND)를 1인칭으로 연기한다(성격·말투는 PoC persona). roleplay-chat.sh 가 학급(:class_id)·목표 해시(:goal_hash)를 넘긴다.
-- 퀴즈 결과로 정해지는 난이도를 미리 알 수 없어서 L1·L2·L3 를 모두 같은 내용으로 넣는다.
-- 승인본은 바꿀 수 없으므로(V20 트리거) DRAFT 로 넣고 목표·첫 질문 초점을 붙인 뒤 APPROVED 로 올린다.
BEGIN;

CREATE TEMP TABLE local_scenario ON COMMIT DROP AS
SELECT gen_random_uuid() AS scenario_id, level AS scenario_level,
       gen_random_uuid() AS g1, gen_random_uuid() AS g2, gen_random_uuid() AS g3
FROM unnest(ARRAY['L1', 'L2', 'L3']) AS level;

INSERT INTO scenario (scenario_id, class_id, goal_content_hash, scenario_version, status, scenario_level,
                      title, summary, ai_role, characters, scenario_facts, prohibited_inferences,
                      opening_prompts, origin, prompt_version, created_at)
SELECT scenario_id, :'class_id'::uuid, :'goal_hash', 1, 'DRAFT', scenario_level,
       '넘어져서 우는 친구',
       '놀이터에서 친구가 미끄럼틀 앞에서 넘어져 울었다.',
       'FRIEND',
       '[{"id": "LEARNER", "role": "학습자"},
         {"id": "FRIEND", "role": "넘어져서 우는 친구",
          "personality": "소심하고 잘 우는 편, 겁이 많지만 다정함",
          "speech_style": "반말, 짧은 문장, 훌쩍이는 소리(훌쩍, 흐윽)를 자주 냄"}]'::jsonb,
       '["친구가 미끄럼틀로 뛰어갔다.", "친구가 넘어졌다.", "친구가 무릎을 잡고 울었다."]'::jsonb,
       '["친구가 일부러 넘어졌다고 단정하지 않는다.", "다른 친구가 밀었다는 등 시나리오에 없는 사건을 지어내지 않는다."]'::jsonb,
       '{"S0": "(훌쩍이며) 흐윽…",
         "S1": "(무릎을 감싸 쥐며) 아야… 나 방금 미끄럼틀 앞에서…",
         "S2": "(울먹이며) 넘어진 건지… 미끄러진 건지… 정신이 없어.",
         "S3": "(울먹이며) 나 미끄럼틀 앞에서 넘어졌어…"}'::jsonb,
       'PREPARED', 'local-test', now()
FROM local_scenario;

INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
SELECT g1, scenario_id, 1, '친구가 넘어졌다는 사건을 파악한다.', '["친구가 넘어졌거나 다쳤다는 것을 말하거나 묻는다."]'::jsonb, '너 넘어졌구나, 괜찮아?' FROM local_scenario
UNION ALL
SELECT g2, scenario_id, 2, '친구가 아프거나 속상한 감정을 파악한다.', '["친구가 아프거나 속상하거나 슬프다는 것을 말하거나 묻는다."]'::jsonb, '많이 아프고 속상하겠다.' FROM local_scenario
UNION ALL
SELECT g3, scenario_id, 3, '넘어진 일과 감정을 연결한다.', '["넘어져서 아프거나 속상하다는 것을 이어서 말한다."]'::jsonb, '넘어져서 아파서 우는 거구나.' FROM local_scenario;

-- 첫 질문은 모두 사건(MG-01)을 묻는다.
INSERT INTO scenario_opening_focus (scenario_id, support_level, micro_goal_id)
SELECT scenario_id, level, g1 FROM local_scenario, unnest(ARRAY['S0', 'S1', 'S2', 'S3']) AS level;

UPDATE scenario SET status = 'APPROVED' WHERE scenario_id IN (SELECT scenario_id FROM local_scenario);

COMMIT;
