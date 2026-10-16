-- 활동 유형과 유형별 배정 규칙
-- 최초 결합(퀴즈 → 역할극, 아동당 1회) · 내 감정 퀴즈 · 상대 감정 퀴즈 · 역할극.
-- V4 의 activity_type 은 값이 늘 'QUIZ_ROLEPLAY' 였다. 그 활동은 모두 퀴즈 → 역할극으로 이어지는 최초 결합 모양이다.
UPDATE activity SET activity_type = 'FIRST_COMBINED' WHERE activity_type = 'QUIZ_ROLEPLAY';
ALTER TABLE activity ALTER COLUMN activity_type DROP DEFAULT;
ALTER TABLE activity ADD CONSTRAINT ck_activity_type
    CHECK (activity_type IN ('FIRST_COMBINED', 'SELF_EMOTION_QUIZ', 'OTHER_EMOTION_QUIZ', 'ROLEPLAY'));

-- 독립 퀴즈는 학습 목표가 없다. 최초 결합·역할극은 목표가 있어야 한다.
ALTER TABLE activity ALTER COLUMN goal_id DROP NOT NULL;
ALTER TABLE activity ADD CONSTRAINT ck_activity_goal_by_type
    CHECK ((activity_type IN ('FIRST_COMBINED', 'ROLEPLAY')) = (goal_id IS NOT NULL));

-- 최초 결합은 아동당 한 번이지만 DB 인덱스로 걸지 않는다. 서버가 아동 행을 잠그고 확인해 막는다.
-- 위 UPDATE 로 이미 있던 활동이 모두 최초 결합이 되므로, 한 아동에게 활동이 둘 이상 있는 DB(dev 서버·팀원 로컬)에서 인덱스를 만들면
-- 이 마이그레이션이 실패한다. 그런 예전 활동은 지우지 않고 그대로 둔다.

-- V6 의 "시작 전 아동·목표 ID 하나" 인덱스는 지운다. 최초 결합은 아동당 1회라 걸릴 일이 없고,
-- 역할극은 목표가 아니라 시나리오로 같은 활동을 판단한다.
DROP INDEX uq_activity_not_started_child_goal;

-- 독립 퀴즈는 (아동, 유형) 기준으로 시작 전 활동을 하나만 둔다.
CREATE UNIQUE INDEX uq_activity_not_started_child_type
    ON activity (child_id, activity_type)
    WHERE status = 'NOT_STARTED' AND activity_type IN ('SELF_EMOTION_QUIZ', 'OTHER_EMOTION_QUIZ');

-- 독립 퀴즈는 10문항이다. 유형별 상한(최초 결합 3, 독립 퀴즈 10)은 서버가 지킨다. CHECK 는 activity 의 유형을 볼 수 없다.
-- V4 가 이름 없이 만든 CHECK 라 PostgreSQL 기본 이름(테이블_열_check)으로 지운다.
ALTER TABLE activity_quiz DROP CONSTRAINT activity_quiz_question_order_check;
ALTER TABLE activity_quiz ADD CONSTRAINT ck_activity_quiz_question_order
    CHECK (question_order BETWEEN 1 AND 10);

-- 시드 문항을 더한다. 문구는 팀 검토 전 예시다(V6 과 같다). 독립 퀴즈 10문항을 채우기엔 모자라 로컬에서는 독립 퀴즈 배정이
-- 422 로 막힌다. 실제 문항은 팀이 채운다.
INSERT INTO quiz_item (
    item_id, quiz_type, emotion, question_text, image_url,
    choices_json, correct_answer, acceptable_answers_json, hints_json, status, item_version
) VALUES
(
    '5eed0000-0000-4000-8000-000000000004', 'SELF_EMOTION_SITUATION', 'SADNESS',
    '아끼던 장난감이 부서졌어. 너는 어떤 기분이 들까?', NULL,
    '["기쁨", "슬픔", "놀람"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "좋아하던 것이 망가지면 눈물이 날 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000005', 'SELF_EMOTION_SITUATION', 'ANGER',
    '친구가 내 그림에 허락 없이 낙서를 했어. 너는 어떤 기분이 들까?', NULL,
    '["화남", "기쁨", "슬픔"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "얼굴이 뜨거워지고 주먹에 힘이 들어갈 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "화남과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000006', 'OTHER_EMOTION_SITUATION', 'JOY',
    '친구가 달리기 시합에서 1등을 했어. 친구는 지금 어떤 기분일까?', NULL,
    '["기쁨", "화남", "슬픔"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "이기면 활짝 웃게 돼요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
);
