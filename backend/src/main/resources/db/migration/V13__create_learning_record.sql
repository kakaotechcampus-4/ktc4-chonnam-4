-- 완료한 활동마다 학습 기록이 정확히 하나 생긴다. 요약·리포트 칸은 리포트 작업이 필요할 때 새 마이그레이션으로 더한다.
-- 퀴즈 수치는 quiz_result 에, 수행 시간은 activity 의 started_at·completed_at 에 이미 있어 여기에 복사하지 않는다.
CREATE TABLE learning_record (
    record_id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activity (activity_id),
    created_at TIMESTAMPTZ NOT NULL,
    -- 서버가 활동 행을 잠그고 한 번만 만든다. 이 제약은 서버 코드가 실수했을 때의 마지막 장치다.
    CONSTRAINT uq_learning_record_activity UNIQUE (activity_id)
);

-- 지금까지는 서버가 활동을 완료로 바꾸지 않아서, 문항을 다 풀어 결과가 있는 독립 퀴즈도 진행 중으로 남아 있다.
-- 결과가 저장된 시각을 완료·스탬프 시각으로 보고 맞춘다. 최초 결합은 역할극까지 마쳐야 완료라 건드리지 않는다.
UPDATE activity a
SET status = 'COMPLETED',
    completed_at = r.snapshotted_at,
    reward_issued_at = r.snapshotted_at
FROM quiz_result r
WHERE r.activity_id = a.activity_id
  AND a.activity_type IN ('SELF_EMOTION_QUIZ', 'OTHER_EMOTION_QUIZ')
  AND a.status <> 'COMPLETED';

INSERT INTO learning_record (record_id, activity_id, created_at)
SELECT gen_random_uuid(), a.activity_id, a.completed_at
FROM activity a
WHERE a.status = 'COMPLETED'
  AND a.completed_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM learning_record l WHERE l.activity_id = a.activity_id);
