-- 퀴즈 문항 풀. 문항을 넣고 고치는 곳은 이 파일 하나다(스키마 변경은 db/migration).
-- Flyway 반복 실행 파일이라 내용이 바뀌면 모든 V 마이그레이션 뒤에 다시 실행된다. 몇 번을 돌려도 결과가 같게 item_id 로 덮어쓴다.
--
-- 고칠 때 지킬 것
-- - 이미 배정된 문항의 문구·보기·정답·힌트는 고치지 않는다. 문항 하나에 행이 하나라, 고치면 지난 풀이 기록이 가리키는 문구도 바뀐다.
--   바꾸려면 새 item_id 로 문항을 더하고, 예전 문항은 status 를 'DRAFT' 로 내린다(배정에서 빠지고 기록은 남는다).
-- - 문항 행은 지우지 않는다. 배정된 문항은 activity_quiz 가 가리키고 있어 지울 수 없다.
-- - 독립 퀴즈는 유형별 승인 문항이 10개 이상이어야 배정된다(상대 감정은 상황 7 + 그림 3).
-- - 실제 아동 정보는 넣지 않는다. 그림 문항의 그림은 사람 사진이 아닌 일러스트(frontend/public/quiz-images)다.
--
-- 아래 6문항은 V6·V10 에서 넣은 팀 검토 전 예시를 그대로 옮긴 것이다.
INSERT INTO quiz_item (
    item_id, quiz_type, emotion, question_text, image_url,
    choices_json, correct_answer, acceptable_answers_json, hints_json, status, item_version
) VALUES
(
    '5eed0000-0000-4000-8000-000000000001', 'SELF_EMOTION_SITUATION', 'JOY',
    '생일에 친구가 선물을 줬어. 너는 어떤 기분이 들까?', NULL,
    '["기쁨", "슬픔", "화남"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "선물을 받으면 웃음이 나요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000002', 'OTHER_EMOTION_SITUATION', 'SADNESS',
    '친구가 좋아하던 풍선이 하늘로 날아가 버렸어. 친구는 지금 어떤 기분일까?', NULL,
    '["기쁨", "슬픔", "놀람"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "좋아하던 것을 잃으면 눈물이 날 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000003', 'OTHER_EMOTION_IMAGE', 'ANGER',
    '이 친구는 지금 어떤 기분일까?', '/quiz-images/sample-anger.svg',
    '["화남", "기쁨", "슬픔"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "눈썹이 아래로 모이고 입이 꾹 다물려 있어요."}, {"type": "CHOICE_REDUCTION", "text": "화남과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
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
)
ON CONFLICT (item_id) DO UPDATE SET
    quiz_type = EXCLUDED.quiz_type,
    emotion = EXCLUDED.emotion,
    question_text = EXCLUDED.question_text,
    image_url = EXCLUDED.image_url,
    choices_json = EXCLUDED.choices_json,
    correct_answer = EXCLUDED.correct_answer,
    acceptable_answers_json = EXCLUDED.acceptable_answers_json,
    hints_json = EXCLUDED.hints_json,
    status = EXCLUDED.status,
    item_version = EXCLUDED.item_version;
