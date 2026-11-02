# 로컬 끝까지 돌려 보기 도구

역할극 리포트·다음 난이도·추천을 실제 백엔드와 실제 LLM으로 끝까지 돌려 볼 때 쓴다. 아동 역할극 화면이 아직 mock이라서, 아동 쪽은 API 드라이버가 대신한다. 음성 인식만 대본으로 바꾼다. 실행 기록은 `docs/roleplay-report-e2e-2026-11-01.md`에 있다.

1. `powershell -File scripts/local/run-backend.ps1`로 DB 컨테이너(55433)와 백엔드를 띄운다. 모델 주소와 키는 `backend/.env.local`의 `AI_API_KEY`, `MODEL_HAIKU55_*`, `MODEL_ARGON4_*`에서 읽고 찍지 않는다. 역할극 HTTP API를 켜고, STT는 대본(`scripted`), TTS는 끔(`silent`), 입력 처리기는 로컬 전용(`roleplay.local-script-input`)이다.
2. 프론트를 5173에 띄우고, 강사로 가입해 학급·아동·최초 결합 활동을 만든다.
3. `powershell -File scripts/local/seed-scenarios.ps1 -ActivityId <활동 ID> -Tag first`로 그 활동의 목표에 맞는 L1~L3 승인 시나리오를 넣는다. 같은 목표로 역할극을 다시 하려면 다른 tag로 한 번 더 넣는다. 역할극 단독 활동은 같은 목표 제목으로 넣어 둔 뒤, 활동 만들기 확인 단계에서 그 시나리오를 골라 배정한다(서버가 시작할 때 고르지 않는다).
4. `RR_EMAIL`, `RR_PASSWORD`(강사 계정)를 환경변수로 주고 `node scripts/local/roleplay-run.mjs --activity <활동 ID> --child <아동 ID> --answers scripts/local/answers/steady.json`을 실행한다. 퀴즈와 역할극을 실제 API로 돌린다. 답 대본은 세 가지다. `steady.json`은 단서를 들으면 해내는 아동, `strong.json`은 열린 질문에 스스로 답하는 아동, `unsure.json`은 모르겠다는 답이 잦은 아동이다. 모델 출력이 서버 검사(80자, 물음표 수 등)에 걸리면 백엔드 로그에 `LLM_OUTPUT_REJECTED`로 어느 검사인지 남는다.

대본 STT, 로컬 입력 처리기, 음성 전달 대체물은 개인정보 거르기와 안전 판단을 하지 않는다. 합성 대본에만 쓰고, 실제 아동 음성이나 운영 설정에는 쓰지 않는다.
