-- 캐릭터 역할극에서 아이가 막혔을 때 캐릭터 대사와 따로 보여 준 마스코트 힌트.
-- 다음 턴의 마스코트 규칙(연속 금지·목표당 상한)과 같은 요청 재전송에 쓴다. 아동 발화가 아니라 보관 정리 대상이 아니다.
ALTER TABLE roleplay_turn
    ADD COLUMN mascot_hint TEXT CHECK (mascot_hint IS NULL OR length(trim(mascot_hint)) > 0);
