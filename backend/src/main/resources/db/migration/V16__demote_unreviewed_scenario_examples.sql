-- 처음 넣은 예시 시나리오 3개(db/seed/R__scenarios.sql)는 팀 검토 전인데 APPROVED 로 들어가 첫 활동에 붙을 수 있었다.
-- 시드 파일은 APPROVED 행을 고치지 않으므로, 이미 그렇게 들어간 DB 는 여기서 한 번 바로잡는다. 새 DB 에서는 아무 행도 바뀌지 않는다.
-- 끝나지 않은 활동에 붙은 예시는 떼어 낸다. 다음 퀴즈 결과 조회 때 승인 시나리오가 있으면 다시 붙는다.
-- 끝난 활동은 기록이라 그대로 둔다.
UPDATE activity
SET scenario_id = NULL
WHERE status <> 'COMPLETED'
  AND scenario_id IN (
      '5ce00000-0000-4000-8000-000000000001',
      '5ce00000-0000-4000-8000-000000000002',
      '5ce00000-0000-4000-8000-000000000003');

UPDATE scenario
SET status = 'DRAFT'
WHERE status = 'APPROVED'
  AND scenario_id IN (
      '5ce00000-0000-4000-8000-000000000001',
      '5ce00000-0000-4000-8000-000000000002',
      '5ce00000-0000-4000-8000-000000000003');
