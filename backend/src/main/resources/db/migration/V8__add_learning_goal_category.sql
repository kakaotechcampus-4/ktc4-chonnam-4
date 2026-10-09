-- 학습 목표 분류(감정 인식·공감 표현·상황 대처). 지금까지 화면이 situation_type 에 담아 보내던 값을 따로 둔다.
-- situation_type 은 원래 뜻(역할극의 상황 유형)으로 비워 둔다. 두 값 모두 목록이 정해지지 않아 문자열로 둔다.
ALTER TABLE learning_goal ADD COLUMN category VARCHAR(30);

-- 이미 저장된 목표의 분류를 옮긴다. content_hash 는 다시 계산하지 않는다(SQL 로 서버와 같은 값을 만들 수 없다).
-- 그래서 옮긴 목표와 새로 저장한 목표는 내용이 같아도 해시가 달라, 둘 사이의 중복 배정 확인만 놓친다.
UPDATE learning_goal
SET category = situation_type,
    situation_type = NULL
WHERE situation_type IN ('EMOTION_RECOGNITION', 'EMPATHY_EXPRESSION', 'SITUATION_COPING');
