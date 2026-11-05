# PR 초안: 역할극 음성 파일 검사와 재입력 연결

- Base: `pado0711/ktc4-chonnam-4:feat/roleplay-input-recovery`
- Head: `pado0711/ktc4-chonnam-4:feat/roleplay-audio-validation`
- 관련 Task: S6-JIN-01 일부

## 변경 내용

- 실제 음성 형식·길이 검사, 배포 이미지의 FFmpeg 도구, 검사 실패의 재입력 및 실패 횟수 기록을 연결했다.
- 업로드 시작 시 서버 처리 기한을 설정했다.
- 크기 초과로 파일 지문을 알 수 없는 요청도 한 번 기록하고 같은 요청 번호 재사용을 충돌로 처리했다.

## 검증

- `./backend/gradlew -p backend --offline --no-daemon spotlessCheck build`: 1,603개 검사 통과, 실패·오류·건너뜀 0개.
- 실제 FFmpeg 바이너리, 브라우저·프록시를 통한 업로드 시작 시점, 외부 STT/LLM/TTS 종단 동작은 별도 확인이 필요하다.

## 후속 연동

- 프론트엔드는 재입력 안내 및 세 번째 실패 이후 텍스트 권유를 표시해야 한다. 브라우저 업로드를 시작한 순간부터 60초 제한을 적용해야 한다.
- 임시 파일 소유·정리와 보관 만료 처리는 `feat/roleplay-input-lifecycle`에서 이어진다.
