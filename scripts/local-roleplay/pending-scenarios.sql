-- 로컬 확인 전용. 웹 화면에서 만든 최초 결합 활동에도 역할극이 열리도록 승인 시나리오를 넣는다. 배포 DB 에 쓰지 않는다.
-- run-backend.sh 가 몇 초마다 실행한다. 여러 번 실행해도 같은 (학급, 목표 해시)에는 한 번만 넣는다.
--
-- 1) 시나리오가 아직 없는 최초 결합 활동의 (학급, 목표 해시)마다 approved-scenario.sql 과 같은 내용의 승인본을 L1·L2·L3 로 넣는다.
--    서버는 아이가 퀴즈를 마치는 순간 같은 학급·목표·난이도의 승인본을 찾아 활동에 붙인다(ScenarioAttachmentService).
-- 2) 승인본이 생기기 전에 퀴즈를 이미 마친 활동은 서버와 같은 조건(같은 학급·목표 해시·최초 난이도, 그 아동이 안 쓴 승인본)으로 바로 붙인다.
BEGIN;

CREATE TEMP TABLE pending ON COMMIT DROP AS
SELECT DISTINCT c.class_id, g.content_hash
FROM activity a
JOIN child c ON c.child_id = a.child_id
JOIN learning_goal g ON g.goal_id = a.goal_id
WHERE a.activity_type = 'FIRST_COMBINED'
  AND a.scenario_id IS NULL
  AND a.status <> 'COMPLETED'
  AND g.content_hash <> ''
  AND NOT EXISTS (
      SELECT 1 FROM scenario s
      WHERE s.class_id = c.class_id AND s.goal_content_hash = g.content_hash AND s.status = 'APPROVED');

CREATE TEMP TABLE local_scenario ON COMMIT DROP AS
SELECT gen_random_uuid() AS scenario_id, p.class_id, p.content_hash, level AS scenario_level,
       gen_random_uuid() AS g1, gen_random_uuid() AS g2, gen_random_uuid() AS g3
FROM pending p, unnest(ARRAY['L1', 'L2', 'L3']) AS level;

INSERT INTO scenario (scenario_id, class_id, goal_content_hash, scenario_version, status, scenario_level,
                      title, summary, ai_role, characters, scenario_facts, prohibited_inferences,
                      opening_prompts, origin, prompt_version, created_at)
SELECT scenario_id, class_id, content_hash, 1, 'DRAFT', scenario_level,
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

-- 승인본보다 퀴즈를 먼저 마친 활동은 서버의 연결 조건 그대로 붙인다.
UPDATE activity a
SET scenario_id = (
    SELECT s.scenario_id FROM scenario s
    WHERE s.status = 'APPROVED'
      AND s.class_id = c.class_id
      AND s.goal_content_hash = g.content_hash
      AND s.scenario_level = r.scenario_level
      AND s.scenario_version > 0
      AND NOT EXISTS (SELECT 1 FROM activity used WHERE used.child_id = a.child_id AND used.scenario_id = s.scenario_id)
    ORDER BY s.created_at, s.scenario_id
    LIMIT 1)
FROM child c, learning_goal g, quiz_result r
WHERE c.child_id = a.child_id
  AND g.goal_id = a.goal_id
  AND r.activity_id = a.activity_id
  AND r.initial_difficulty_used
  AND r.scenario_level IS NOT NULL
  AND a.activity_type = 'FIRST_COMBINED'
  AND a.scenario_id IS NULL
  AND a.status <> 'COMPLETED';

COMMIT;
