# 역할극 영구 삭제 책임 (PR #49 R6)

## 정책과 실행 경로

아동·학급 영구 삭제 시 역할극 기록도 삭제한다. 삭제 범위와 순서는 `DeletionService`가 조립한다.
V7에 있던 activity → session → turn CASCADE는 V8에서 NO ACTION FK로 전환한다.
적용 이력과 체크섬을 보존하기 위해 V7은 수정하지 않는다.

`deleteChild` 또는 `deleteClassroom`의 기존 @Transactional 안에서:

1. 학급 삭제라면 학급을 먼저 잠근다.
2. 대상 활동 → 아동 → 역할극 세션을 종류별 ID 순서로 잠근다.
3. 기존 퀴즈 종속 데이터를 삭제한다.
4. 대상 아동의 역할극 턴 → 세션을 명시적으로 일괄 삭제한다.
5. 활동 → 목표 → 입장 코드 → 아동을 삭제한다. 학급 삭제는 마지막에 학급도 삭제한다.

삭제 중 하나라도 실패하면 전체 트랜잭션이 롤백된다. 다른 아동의 세션/턴은 삭제 대상이 아니다.
FK는 유지한다. 종속 데이터를 남긴 채 활동이나 세션만 삭제하면 DB가 삭제를 거부한다.

## 순환 FK와 잠금

세션의 last_turn_id는 턴을 참조하고, 턴은 세션을 참조한다. V7의 fk_session_last_turn은
DEFERRABLE INITIALLY DEFERRED이므로 턴을 삭제한 직후 세션도 같은 트랜잭션에서 삭제하면
커밋 시 참조가 남지 않는다. 턴 삭제만 별도로 커밋해서는 안 된다.
last_turn_id를 임시 NULL로 바꾸면 last_turn_number와의 CHECK 조건도 다뤄야 하므로,
이 삭제 경로에서는 세션 상태를 임시 변경하지 않는다.

턴을 지우기 전 세션 잠금을 획득한다. 체크포인트 저장(활동 → 아동 → 세션)과
표준 발화 정리(세션 → 턴 갱신)에 맞춰 순환 대기를 피한다.
외부 AI 호출 중에는 이 삭제 트랜잭션을 유지하지 않는다. 삭제가 먼저 확정되면
나중에 완료되는 역할극 작업은 최종 저장의 소유권/존재 검사에서 거절된다.

## 표준 발화 정리와 차이

영구 삭제는 세션과 턴 행 전체를 제거한다. 정상 종료 또는 미완료 보관 기간 정리는
canonical_utterance만 NULL로 바꾸며 응답/재전송 체크포인트를 남긴다.
음성 저장소·제공자·백업의 삭제 정책까지 처리하는 변경은 아니다.

## 배포와 검증 범위

V8과 명시적 삭제 코드를 같은 릴리스로 적용한다. V8 적용 후 예전 삭제 코드만 재배포하면
역할극 종속 행이 있는 아동/학급의 삭제가 FK 오류로 실패할 수 있다.
SQL 제약 이름은 기존 V7에서 PostgreSQL이 부여하는 이름을 기준으로 한다.
실제 PostgreSQL 18.6-alpine Testcontainers에서 아래 테스트 40개가 통과했다.

- RoleplayDeletionIntegrationTest: 새 7개. 아동 삭제/다른 아동 보존, 학급 삭제/다른 학급 보존,
  부모 단독 삭제 거부, 상위 트랜잭션 실패 시 롤백, 턴만 삭제했을 때 커밋 시 지연 FK 거부,
  신규 DB V8 적용, 별도 스키마의 기존 V7 데이터에 V8 적용과 데이터 보존.
- DeletionIntegrationTest: 기존 7개.
- RoleplayCheckpointIntegrationTest: 기존 17개.
- RoleplayCanonicalRetentionIntegrationTest: 기존 9개.

전환 테스트는 애플리케이션 풀과 별도의 연결로 격리 스키마를 사용한다.
테스트 메서드 전체에 롤백 트랜잭션을 걸지 않아 실제 커밋의 지연 FK 검사까지 확인한다.
운영 DB·외부 AI는 사용하지 않는다. 삭제와 역할극 저장/정리를 동시에 실행하는 전용 시나리오는
이번 실행에 추가하지 않았다.

재실행(backend 디렉터리, Docker 실행 필요):

```bash
./gradlew test \
  --tests 'com.neuringo.neuringobe.RoleplayDeletionIntegrationTest' \
  --tests 'com.neuringo.neuringobe.deletion.DeletionIntegrationTest' \
  --tests 'com.neuringo.neuringobe.RoleplayCheckpointIntegrationTest' \
  --tests 'com.neuringo.neuringobe.RoleplayCanonicalRetentionIntegrationTest'
```

보고서: `build/reports/tests/test/index.html`.
