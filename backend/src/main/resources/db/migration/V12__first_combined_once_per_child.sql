-- 최초 결합은 아동당 하나. V10 에서 서버만 지키던 규칙을 DB 에서도 막는다.
-- V10 이 예전 활동을 모두 최초 결합으로 바꿔서, 한 아동에게 활동이 둘 이상 있던 DB(dev 서버·팀원 로컬)에는 최초 결합이 여러 개다.
-- 그런 데이터는 테스트 데이터뿐이라, 아동별로 가장 먼저 배정된 하나만 남기고 나머지는 붙은 기록과 함께 지운다.
-- 지우는 순서는 DeletionService.deleteChildren 과 같다. 학습 목표는 남은 활동이 쓸 수 있어 지우지 않는다.
CREATE TEMPORARY TABLE extra_first_combined ON COMMIT DROP AS
SELECT activity_id
FROM (
    SELECT activity_id,
           row_number() OVER (PARTITION BY child_id ORDER BY assigned_at, activity_id) AS rn
    FROM activity
    WHERE activity_type = 'FIRST_COMBINED'
) ranked
WHERE rn > 1;

DELETE FROM quiz_hint
WHERE attempt_id IN (
    SELECT qa.attempt_id
    FROM quiz_attempt qa
    JOIN activity_quiz aq ON aq.activity_quiz_id = qa.activity_quiz_id
    WHERE aq.activity_id IN (SELECT activity_id FROM extra_first_combined)
);
DELETE FROM quiz_attempt
WHERE activity_quiz_id IN (
    SELECT activity_quiz_id FROM activity_quiz
    WHERE activity_id IN (SELECT activity_id FROM extra_first_combined)
);
DELETE FROM quiz_result WHERE activity_id IN (SELECT activity_id FROM extra_first_combined);
DELETE FROM activity_quiz WHERE activity_id IN (SELECT activity_id FROM extra_first_combined);

-- 세션의 마지막 턴 FK 는 커밋 때 확인한다(V7). 턴과 세션을 이 트랜잭션 안에서 함께 지우면 걸리지 않는다.
DELETE FROM roleplay_turn
WHERE session_id IN (
    SELECT session_id FROM roleplay_session
    WHERE activity_id IN (SELECT activity_id FROM extra_first_combined)
);
DELETE FROM roleplay_session WHERE activity_id IN (SELECT activity_id FROM extra_first_combined);

DELETE FROM activity WHERE activity_id IN (SELECT activity_id FROM extra_first_combined);

-- 서버가 아동 행을 잠그고 먼저 확인한다. 이 인덱스는 서버 코드가 실수했을 때의 마지막 장치다.
CREATE UNIQUE INDEX uq_activity_first_combined_child
    ON activity (child_id)
    WHERE activity_type = 'FIRST_COMBINED';
