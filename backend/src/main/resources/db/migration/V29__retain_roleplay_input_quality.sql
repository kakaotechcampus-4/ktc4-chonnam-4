-- 과거 턴의 품질 정보는 추측하지 않으며 표준 발화와 함께 삭제한다.
ALTER TABLE roleplay_turn
    ADD COLUMN input_quality JSONB,
    ADD CONSTRAINT ck_roleplay_turn_input_quality_retention CHECK (
        input_quality IS NULL OR
        (canonical_utterance IS NOT NULL AND jsonb_typeof(input_quality) = 'object')
    );
