-- 활동 배정 (ADR 2026-10-03 D4, S1-BAE-02 D3·D5)

-- 배정 요청의 Idempotency-Key. 응답을 못 받은 화면이 같은 키로 다시 보내면 처음 만든 활동을 돌려준다.
-- 이 열이 생기기 전의 활동은 키가 없어 NULL 이다. PostgreSQL UNIQUE 는 NULL 끼리 겹쳐도 허용한다.
ALTER TABLE activity ADD COLUMN idempotency_key UUID;
CREATE UNIQUE INDEX uq_activity_idempotency_key ON activity (idempotency_key);

-- 같은 아동·같은 목표로 시작 전 활동은 하나만 둔다(D5). 서버가 아동 행을 잠가 먼저 막고, 이 인덱스는 마지막 장치다.
CREATE UNIQUE INDEX uq_activity_not_started_child_goal
    ON activity (child_id, goal_id)
    WHERE status = 'NOT_STARTED';

-- 최초 퀴즈 문항 풀의 시드(D3). 유형마다 1개, 모두 APPROVED.
-- 문구·선택지·정답·힌트는 팀 검토용 예시다. 느린 학습자에게 맞는지 검토한 뒤 새 버전으로 바꾼다. 실제 아동 정보는 넣지 않는다.
-- 이미지 문항의 그림은 사람 사진이 아닌 예시 일러스트(frontend/public/quiz-images)다.
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
);
