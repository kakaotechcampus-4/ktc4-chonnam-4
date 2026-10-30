-- 로컬 확인용 승인 시나리오. roleplay-chat.sh 가 학급(:class_id)·목표 해시(:goal_hash)를 넘긴다.
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
       'NARRATOR',
       '[{"id": "LEARNER", "role": "학습자"}, {"id": "FRIEND", "role": "넘어진 친구"}]'::jsonb,
       '["친구가 미끄럼틀로 뛰어갔다.", "친구가 넘어졌다.", "친구가 무릎을 잡고 울었다."]'::jsonb,
       '["친구가 일부러 넘어졌다고 단정하지 않는다.", "다른 친구가 밀었다는 등 시나리오에 없는 사건을 지어내지 않는다."]'::jsonb,
       '{"S0": "아까 놀이터에서 무슨 일이 있었는지 봤어?",
         "S1": "친구가 미끄럼틀 앞에서 울고 있어. 무슨 일이 있었을까?",
         "S2": "친구가 넘어졌을까, 아니면 장난감을 잃어버렸을까?",
         "S3": "친구가 넘어졌대. 친구에게 무슨 일이 있었는지 말해 볼까?"}'::jsonb,
       'PREPARED', 'local-test', now()
FROM local_scenario;

INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
SELECT g1, scenario_id, 1, '친구가 넘어졌다는 사건을 파악한다.', '["넘어짐 또는 다침을 말한다."]'::jsonb, '친구가 넘어졌어.' FROM local_scenario
UNION ALL
SELECT g2, scenario_id, 2, '친구가 아프거나 속상한 감정을 파악한다.', '["아픔·속상함·슬픔 같은 감정을 말한다."]'::jsonb, '친구가 아프고 속상해.' FROM local_scenario
UNION ALL
SELECT g3, scenario_id, 3, '넘어진 일과 감정을 연결한다.', '["넘어져서 그런 감정이 들었다고 이어 말한다."]'::jsonb, '넘어져서 아파서 울었어.' FROM local_scenario;

-- 첫 질문은 모두 사건(MG-01)을 묻는다.
INSERT INTO scenario_opening_focus (scenario_id, support_level, micro_goal_id)
SELECT scenario_id, level, g1 FROM local_scenario, unnest(ARRAY['S0', 'S1', 'S2', 'S3']) AS level;

UPDATE scenario SET status = 'APPROVED' WHERE scenario_id IN (SELECT scenario_id FROM local_scenario);

COMMIT;
