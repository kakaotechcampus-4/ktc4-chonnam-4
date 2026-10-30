# 로컬에서 아이와의 역할극 대화 확인하기

실제 LLM 으로 역할극 대화가 어떻게 이어지는지 로컬에서 본다. 웹 화면 없이 API 로만 돈다.

지금 develop 에는 이 흐름에 빈 곳이 있어서, 여기서는 임시로 채운다. **배포 설정이 아니다.**

| 빈 곳 | 여기서 채우는 방법 |
| --- | --- |
| 시나리오 생성·승인 API 없음 | 승인 시나리오(L1·L2·L3)를 DB 에 직접 넣는다 (`approved-scenario.sql`) |
| 역할극 음성 API 기본 꺼짐 | `roleplay.http.enabled=true` 와 업로드·턴 제한 값을 넣어 띄운다 |
| 아이 입력 안전 검사(SAFE) 구현 없음 | 안전 검사 없이 통과시킨다 (`LocalRoleplayBeans.java`) |
| STT·TTS | 녹음 대신 문장을 "음성" 파일로 보내 글자로 읽고, TTS 는 끈다 |
| 안내 문구 승인본 없음 | 종류 이름이 보이는 로컬 문구를 넣는다. 예: `(로컬 안내: SAFETY_CHECK_ERROR)` |

## 준비

- JDK 21, `curl`, `jq`, Docker(로컬 DB)
- `backend/.env`: DB 접속 값 (`backend/.env.example` 참고)
- `backend/.env.local`: LLM 키 (`scenarioEval` 과 같은 파일)

```
AI_BASE_URL=https://api.openai.com/v1
AI_API_KEY=...
AI_MODEL=gpt-5.4-nano
```

OpenAI 에 직접 붙을 때는 `AI_BASE_URL` 을 `/v1` 까지 쓴다. 빠지면 모든 요청이 `PROVIDER_REQUEST_REJECTED` 로 실패한다.

## 실행

```bash
cd backend && docker compose up -d && cd ..
bash scripts/local-roleplay/run-backend.sh
```

다른 터미널에서 대화한다.

```bash
bash scripts/local-roleplay/roleplay-chat.sh
```

아이 대답을 한 줄씩 입력하고, 빈 줄을 입력하면 끝난다. 문장을 인자로 주면 차례로 보내고 끝난다.

```bash
bash scripts/local-roleplay/roleplay-chat.sh "친구가 미끄럼틀 앞에서 넘어졌어" "아프고 속상했을 것 같아"
```

`roleplay-chat.sh` 는 실행할 때마다 강사·학급·아동·활동을 새로 만든다. 순서는 다음과 같다.

1. 강사 가입·로그인
2. 학급·아동·첫 활동(퀴즈 + 역할극) 만들기
3. 승인 시나리오 넣기
4. 아동 입장, 퀴즈 풀기
5. 역할극 시작(첫 질문), 대화

퀴즈는 모두 첫 번째 보기로 답한다. 최초 난이도는 이 답에 따라 정해진다.

## 결과 읽기

```
AI: 친구가 넘어졌을까, 아니면 장난감을 잃어버렸을까?
아이: 친구가 미끄럼틀 앞에서 넘어졌어
AI: 넘어진 뒤에 친구는 아프고 속상했을까, 슬펐을까?
    (DELIVERED)
```

| 괄호 안 상태 | 뜻 |
| --- | --- |
| `DELIVERED` | AI 대답이 검사를 통과해 전달됨 |
| `RETRY_REQUIRED · RETRY_TURN` + `SAFETY_CHECK_ERROR` | AI 대답 검사(RESPONSE_EVALUATION)가 실패함. 스크립트가 같은 말을 한 번 더 보낸다 |
| `REINPUT_REQUIRED` + `SAFE_FALLBACK` | AI 대답 만들기(RESPONSE_GENERATION)가 재시도 끝에 실패함 |
| `REINPUT_REQUIRED` + `INPUT_CONFIRMATION` | 원인 분석(CAUSE_ANALYSIS)이 재시도 끝에 실패함 |

**LLM 기록:** 백엔드는 개인정보 때문에 LLM 입력·출력을 로그에 남기지 않는다. 로컬에서는 `backend/build/local-roleplay/llm-trace.log` 에 호출마다 종류·입력·출력을 남긴다. 위처럼 실패한 턴은 이 파일에서 모델이 무엇을 냈는지 보면 된다. 끄려면 `LOCAL_LLM_TRACE=` 로 띄운다.

## 직접 넣은 시나리오 바꾸기

`approved-scenario.sql` 의 사실·목표·첫 질문을 고치면 다음 실행부터 반영된다. 승인본은 DB 트리거로 수정이 막혀 있어서, 이 SQL 은 DRAFT 로 넣고 목표를 붙인 뒤 APPROVED 로 올린다.
