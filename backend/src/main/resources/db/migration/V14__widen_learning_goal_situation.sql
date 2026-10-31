-- 역할극 추가 조건의 상황 설명 문장("쉬는 시간에 친구가 그림을 망쳐 속상해하고 있다.")을 situation_type 에 담는다.
-- 시안에 상황 유형 목록이 없고 생성 입력도 자유 문자열이라, 칸 이름은 두고 길이만 목표 제목과 같게 늘린다.
ALTER TABLE learning_goal ALTER COLUMN situation_type TYPE VARCHAR(200);
