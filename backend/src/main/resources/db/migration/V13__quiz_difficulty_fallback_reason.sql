ALTER TABLE quiz_result ADD COLUMN difficulty_fallback_reason VARCHAR(40);

-- 과거 기본값은 원인을 단정할 수 없어 별도 값으로 보존한다.
UPDATE quiz_result
SET difficulty_fallback_reason = 'LEGACY_UNSPECIFIED'
WHERE difficulty_fallback_applied;

-- 구버전 서버가 사유 없이 저장하는 경우도 허용한다.
ALTER TABLE quiz_result ADD CONSTRAINT ck_quiz_result_fallback_reason
CHECK (
    difficulty_fallback_reason IS NULL
    OR (difficulty_fallback_applied
        AND difficulty_fallback_reason IN ('NO_VALID_QUESTIONS', 'LEGACY_UNSPECIFIED'))
);
