# PR 초안: 역할극 입력 보관과 임시 파일 수명주기

- Base: `pado0711/ktc4-chonnam-4:feat/roleplay-audio-validation`
- Head: `pado0711/ktc4-chonnam-4:feat/roleplay-input-lifecycle`
- 관련 Task: S6-JIN-01 일부

## 변경 내용

- 서버 소유 업로드 디렉터리와 소유권 잠금, 재시작 시 남은 알려진 업로드 파일 정리를 연결했다.
- 음성·텍스트 입력과 만료 삭제의 동시 실행을 세션별 권한으로 조정했다.
- 정상 완료 및 미완료 24시간 만료에서 표준 발화·입력 품질·음성 실패 기록을 함께 삭제한다.

## 검증

- `./backend/gradlew -p backend --offline --no-daemon spotlessCheck build`: 1,613개 검사 통과, 실패·오류·건너뜀 0개.
- 실제 브라우저·프록시 업로드 시작 시점, 다중 서버 공유 잠금, 외부 음성·모델 제공자 종단 동작은 별도 확인이 필요하다.

## 후속 연동

- 프론트엔드는 업로드 시작 시점부터 60초 제한을 적용하고, 재입력·텍스트 권유 응답을 표시해야 한다.
- 지원 조정 브랜치와의 결합 검증 및 최종 PR 순서 정리가 필요하다.
