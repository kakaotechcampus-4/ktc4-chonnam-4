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
-- 문항 쓰는 규칙
-- - 정답 감정은 아동 화면에 표정 그림이 있는 다섯 가지(기쁨·슬픔·화남·놀람·속상함)만 쓴다. 무서움·아픔·편안함은 표정 그림이 생기면 더한다.
-- - 유형마다 다섯 감정을 고르게 둔다. 지금은 나의 감정 상황 15(감정마다 3), 상대 감정 상황 10(감정마다 2), 그림 5(감정마다 1)다.
-- - 보기는 3개이고, 상황만 보고도 정답이 하나로 정해지게 고른다. 슬픔과 속상함은 헷갈리기 쉬워 한 문항의 보기에 같이 넣지 않는다.
--   갑자기 일어난 일이 아니면 놀람을 오답 보기로 쓰고, 기쁜 깜짝 일(생일 깜짝 파티 등)은 기쁨과 놀람이 겹쳐 쓰지 않는다.
-- - 힌트는 두 개다. 첫째는 몸·얼굴 단서(나의 감정·상대 감정 상황은 OBSERVABLE_BODY_CUE, 그림은 OBSERVABLE_FACE_CUE), 둘째는 정답과 오답 하나만 남기는 CHOICE_REDUCTION 이다.
-- - 상대 감정 상황은 주어만 친구로 바꾼 나의 감정 문항이 되지 않게 쓴다. 친구의 말·행동을 단서로 넣거나(단서 읽기), 나와 친구 마음이 다른 상황으로 쓴다(관점 차이).
--   첫 힌트는 정답 표정을 말하지 않고, 문장 속 단서를 다시 보게 한다.
-- - 그림 파일 이름에는 정답 감정을 쓰지 않는다(face-01.svg 처럼). 화면이 경로나 이름을 읽어 줄 때 답이 새지 않게 한다.
--
-- 아래 6문항은 V6·V10 에서 넣은 팀 검토 전 예시를 그대로 옮긴 것이다.
-- 그중 상대 감정 상황 2문항(…0002, …0006)은 위 규칙에 맞지 않아 DRAFT 로 내렸다. 지난 풀이 기록이 가리킬 수 있어 지우지 않는다.
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
    'DRAFT', 1
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
    'DRAFT', 1
),
-- 나의 감정 상황: 기쁨
(
    '5eed0000-0000-4000-8000-000000000007', 'SELF_EMOTION_SITUATION', 'JOY',
    '놀이터에서 친구가 "같이 놀자!" 하고 손을 내밀었어. 너는 어떤 기분이 들까?', NULL,
    '["슬픔", "기쁨", "화남"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "같이 놀자는 말을 들으면 입꼬리가 올라가요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000008', 'SELF_EMOTION_SITUATION', 'JOY',
    '선생님이 "정리를 참 잘했네!" 하고 칭찬해 주셨어. 너는 어떤 기분이 들까?', NULL,
    '["화남", "슬픔", "기쁨"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "칭찬을 들으면 가슴이 뿌듯하고 웃음이 나요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 나의 감정 상황: 슬픔
(
    '5eed0000-0000-4000-8000-000000000009', 'SELF_EMOTION_SITUATION', 'SADNESS',
    '매일 같이 놀던 친구가 멀리 이사를 가게 됐어. 너는 어떤 기분이 들까?', NULL,
    '["기쁨", "화남", "슬픔"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친한 친구와 헤어지면 눈물이 나고 힘이 빠질 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000010', 'SELF_EMOTION_SITUATION', 'SADNESS',
    '내가 물을 주며 키우던 꽃이 시들어 버렸어. 너는 어떤 기분이 들까?', NULL,
    '["슬픔", "기쁨", "화남"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "아끼던 것이 시들면 마음이 무겁고 눈물이 날 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 나의 감정 상황: 화남
(
    '5eed0000-0000-4000-8000-000000000011', 'SELF_EMOTION_SITUATION', 'ANGER',
    '줄을 서서 기다리는데 누가 내 앞으로 새치기를 했어. 너는 어떤 기분이 들까?', NULL,
    '["기쁨", "화남", "놀람"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "얼굴이 뜨거워지고 목소리가 커질 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "화남과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000012', 'SELF_EMOTION_SITUATION', 'ANGER',
    '친구가 내 간식을 물어보지도 않고 다 먹어 버렸어. 너는 어떤 기분이 들까?', NULL,
    '["놀람", "기쁨", "화남"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "주먹에 힘이 들어가고 숨이 씩씩 거칠어질 수 있어요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 나의 감정 상황: 놀람
(
    '5eed0000-0000-4000-8000-000000000013', 'SELF_EMOTION_SITUATION', 'SURPRISE',
    '조용한 교실에서 갑자기 "쾅!" 하고 큰 소리가 났어. 너는 어떤 기분이 들까?', NULL,
    '["슬픔", "놀람", "기쁨"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "갑자기 큰 소리가 나면 몸이 움찔하고 눈이 커져요."}, {"type": "CHOICE_REDUCTION", "text": "놀람과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000014', 'SELF_EMOTION_SITUATION', 'SURPRISE',
    '상자를 열었더니 장난감 개구리가 "폴짝" 튀어나왔어. 너는 어떤 기분이 들까?', NULL,
    '["놀람", "화남", "슬픔"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "생각하지 못한 것이 튀어나오면 \"앗!\" 소리가 나와요."}, {"type": "CHOICE_REDUCTION", "text": "놀람과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000015', 'SELF_EMOTION_SITUATION', 'SURPRISE',
    '길을 걷는데 바로 앞으로 고양이가 휙 뛰어내렸어. 너는 어떤 기분이 들까?', NULL,
    '["화남", "슬픔", "놀람"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "깜짝 놀라면 숨을 \"헉\" 하고 들이쉬게 돼요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 놀람 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 나의 감정 상황: 속상함
(
    '5eed0000-0000-4000-8000-000000000016', 'SELF_EMOTION_SITUATION', 'UPSET',
    '줄넘기를 열 번 넘고 싶었는데 자꾸 발에 걸렸어. 너는 어떤 기분이 들까?', NULL,
    '["기쁨", "속상함", "놀람"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "잘 안 되면 한숨이 나오고 입이 삐죽 나와요."}, {"type": "CHOICE_REDUCTION", "text": "속상함과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000017', 'SELF_EMOTION_SITUATION', 'UPSET',
    '열심히 연습한 노래를 발표할 때 자꾸 틀렸어. 너는 어떤 기분이 들까?', NULL,
    '["속상함", "기쁨", "놀람"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "열심히 했는데 잘 안 되면 마음이 콕콕 아프고 한숨이 나와요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 속상함 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000018', 'SELF_EMOTION_SITUATION', 'UPSET',
    '소풍 가는 날만 기다렸는데 비가 와서 못 가게 됐어. 너는 어떤 기분이 들까?', NULL,
    '["놀람", "기쁨", "속상함"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "기다리던 일을 못 하게 되면 입이 삐죽 나오고 힘이 빠져요."}, {"type": "CHOICE_REDUCTION", "text": "속상함과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 상대 감정 상황: 기쁨
-- 단서 읽기
(
    '5eed0000-0000-4000-8000-000000000019', 'OTHER_EMOTION_SITUATION', 'JOY',
    '친구가 받아쓰기 시험지를 받더니 "100점이다!" 하고 시험지를 높이 흔들었어. 친구는 지금 어떤 기분일까?', NULL,
    '["기쁨", "속상함", "화남"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구가 시험지를 어떻게 했는지 다시 봐요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 속상함 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 관점 차이
(
    '5eed0000-0000-4000-8000-000000000031', 'OTHER_EMOTION_SITUATION', 'JOY',
    '나는 매운 떡볶이를 못 먹어. 그런데 친구는 매운 떡볶이를 제일 좋아해. 오늘 급식에 매운 떡볶이가 나왔어. 친구는 지금 어떤 기분일까?', NULL,
    '["기쁨", "속상함", "화남"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "내가 아니라 친구가 좋아하는 음식을 떠올려 봐요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 속상함 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 상대 감정 상황: 슬픔
-- 단서 읽기
(
    '5eed0000-0000-4000-8000-000000000020', 'OTHER_EMOTION_SITUATION', 'SADNESS',
    '친구가 쉬는 시간에 혼자 의자에 앉아 고개를 푹 숙이고, 손등으로 눈을 비비고 있어. 친구는 지금 어떤 기분일까?', NULL,
    '["슬픔", "화남", "기쁨"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구가 손으로 어디를 비비고 있는지 봐요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 관점 차이
(
    '5eed0000-0000-4000-8000-000000000032', 'OTHER_EMOTION_SITUATION', 'SADNESS',
    '나는 그 인형을 처음 봐서 별 느낌이 없어. 그런데 친구는 아기 때부터 그 인형을 안고 잤대. 친구가 그 인형을 잃어버렸어. 친구는 지금 어떤 기분일까?', NULL,
    '["슬픔", "화남", "기쁨"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구에게 그 인형이 얼마나 소중했는지 생각해 봐요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 상대 감정 상황: 화남
-- 단서 읽기
(
    '5eed0000-0000-4000-8000-000000000021', 'OTHER_EMOTION_SITUATION', 'ANGER',
    '친구가 주먹을 꽉 쥐고 발을 쾅쾅 구르며 "내 차례였잖아!" 하고 소리쳤어. 친구는 지금 어떤 기분일까?', NULL,
    '["화남", "슬픔", "기쁨"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구의 주먹과 목소리를 살펴봐요."}, {"type": "CHOICE_REDUCTION", "text": "화남과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 관점 차이
(
    '5eed0000-0000-4000-8000-000000000022', 'OTHER_EMOTION_SITUATION', 'ANGER',
    '나는 공놀이를 별로 안 좋아해. 그런데 친구는 공놀이를 정말 좋아해. 친구가 공을 차려는데 다른 아이가 공을 뺏어서 혼자 가져갔어. 친구는 지금 어떤 기분일까?', NULL,
    '["화남", "슬픔", "기쁨"]', '화남', '["화남"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "다른 아이가 공을 어떻게 가져갔는지 다시 봐요."}, {"type": "CHOICE_REDUCTION", "text": "화남과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 상대 감정 상황: 놀람
-- 단서 읽기
(
    '5eed0000-0000-4000-8000-000000000023', 'OTHER_EMOTION_SITUATION', 'SURPRISE',
    '친구가 책을 펼치자 종이 나비가 툭 튀어나왔어. 친구가 "앗!" 하고 몸을 뒤로 젖혔어. 친구는 지금 어떤 기분일까?', NULL,
    '["놀람", "슬픔", "화남"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구의 몸이 어떻게 움직였는지 봐요."}, {"type": "CHOICE_REDUCTION", "text": "놀람과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 관점 차이
(
    '5eed0000-0000-4000-8000-000000000024', 'OTHER_EMOTION_SITUATION', 'SURPRISE',
    '나는 문 뒤에 친구 동생이 숨어 있는 걸 봤어. 친구는 몰라. 친구가 문을 열자 동생이 "짠!" 하고 튀어나왔어. 친구는 지금 어떤 기분일까?', NULL,
    '["놀람", "슬픔", "화남"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구는 동생이 숨어 있는 걸 알았을까요?"}, {"type": "CHOICE_REDUCTION", "text": "놀람과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 상대 감정 상황: 속상함
-- 단서 읽기
(
    '5eed0000-0000-4000-8000-000000000025', 'OTHER_EMOTION_SITUATION', 'UPSET',
    '친구가 퍼즐을 거의 다 맞췄는데 마지막 조각이 없었어. 친구가 입을 삐죽 내밀고 "에이, 다 했는데…" 하고 작게 말했어. 친구는 지금 어떤 기분일까?', NULL,
    '["속상함", "화남", "기쁨"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구 목소리가 컸는지 작았는지 생각해 봐요."}, {"type": "CHOICE_REDUCTION", "text": "속상함과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 관점 차이
(
    '5eed0000-0000-4000-8000-000000000026', 'OTHER_EMOTION_SITUATION', 'UPSET',
    '나는 그림을 대충 그려도 괜찮아. 그런데 친구는 대회에 내려고 일주일 동안 그림을 그렸어. 결과 발표 날, 친구는 상을 못 받았어. 친구는 지금 어떤 기분일까?', NULL,
    '["속상함", "기쁨", "놀람"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_BODY_CUE", "text": "친구가 그림을 얼마나 오래 그렸는지 생각해 봐요."}, {"type": "CHOICE_REDUCTION", "text": "속상함과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
-- 그림: 기쁨·슬픔·놀람·속상함 (화남은 위의 예시 문항)
(
    '5eed0000-0000-4000-8000-000000000027', 'OTHER_EMOTION_IMAGE', 'JOY',
    '이 친구는 지금 어떤 기분일까?', '/quiz-images/face-01.svg',
    '["기쁨", "화남", "슬픔"]', '기쁨', '["기쁨"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "눈이 반달처럼 휘고 입을 크게 벌려 웃고 있어요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 화남 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000028', 'OTHER_EMOTION_IMAGE', 'SADNESS',
    '이 친구는 지금 어떤 기분일까?', '/quiz-images/face-02.svg',
    '["화남", "슬픔", "기쁨"]', '슬픔', '["슬픔"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "눈썹 안쪽이 올라가고 눈물이 흘러요."}, {"type": "CHOICE_REDUCTION", "text": "슬픔과 기쁨 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000029', 'OTHER_EMOTION_IMAGE', 'SURPRISE',
    '이 친구는 지금 어떤 기분일까?', '/quiz-images/face-03.svg',
    '["슬픔", "기쁨", "놀람"]', '놀람', '["놀람"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "눈이 동그랗게 커지고 입이 동그랗게 벌어졌어요."}, {"type": "CHOICE_REDUCTION", "text": "놀람과 슬픔 중에서 골라 봐요."}]',
    'APPROVED', 1
),
(
    '5eed0000-0000-4000-8000-000000000030', 'OTHER_EMOTION_IMAGE', 'UPSET',
    '이 친구는 지금 어떤 기분일까?', '/quiz-images/face-04.svg',
    '["속상함", "기쁨", "놀람"]', '속상함', '["속상함"]',
    '[{"type": "OBSERVABLE_FACE_CUE", "text": "눈썹이 축 처지고 입이 삐죽 구불구불해요."}, {"type": "CHOICE_REDUCTION", "text": "기쁨과 속상함 중에서 골라 봐요."}]',
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
