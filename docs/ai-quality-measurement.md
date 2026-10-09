# AI 품질 회귀 측정 (S2-LEE-01 · S3-LEE-01)

역할극 한 턴 프롬프트 v1을 같은 입력으로 반복 실행해서 **지연·토큰·비용·형식 실패율·PASS율**을 잰다. 모델·프롬프트·제한 시간을 바꿀 때 전과 비교하는 기준으로 쓴다.

이 문서는 측정 방법을 다룬다. 측정 결과와 권장값(타임아웃·신뢰도)은 실제 모델로 잰 뒤 결과 문서에 따로 정리한다. 무엇을 담을지는 [결과 문서에 담을 것](#결과-문서에-담을-것)에 적었다.

## 구성

| 무엇 | 위치 |
| --- | --- |
| 품질 회귀 세트 (고정 입력 14턴) | `backend/src/test/resources/eval/roleplay-regression-v1.json` |
| 측정 실행기 | `backend/src/test/java/.../ai/eval/` (`./gradlew roleplayEval`) |
| 실행기 단위 테스트 (키 없이 가짜 모델로) | `RoleplayEvalRunnerTest`. 일반 `./gradlew build`에 포함 |
| 결과 | `backend/build/eval/<run-id>/` (커밋하지 않음) |

실행기는 운영과 같은 경로를 쓴다. 프롬프트 v1로 요청을 만들고, 출력 검사(`RoleplayOutputParsers`)를 거치고, `RetryingRoleplayTurnExecutor`가 재시도한다. 실제 모델은 운영 설정(`AiProviderConfiguration`)과 같은 Spring AI 클라이언트로 부른다. HTTP·저장은 거치지 않는다.

## 품질 회귀 세트

PoC 공통 14턴([테스트 대본](poc/2026-09-12-poc-test-scenario.md))을 프롬프트 v1 입력으로 옮겼다.

PoC와 다른 점:

- **각 턴의 입력을 미리 고정했다.** PoC는 앞 턴의 모델 답변이 다음 턴 입력에 들어가서, 모델마다 입력이 달라졌다. 여기서는 턴마다 상태(현재 목표·지원 수준·턴 수)와 최근 대화를 정해 두었다. 모델이 달라도 같은 질문을 받는다.
- **시나리오는 PoC 설명으로 다시 만들었다.** `SCN-GEN-16DE81D6`의 원본 DB 값은 저장소에 없다. 그래서 제목과 캐릭터 상황을 보고 사실 3개, 금지 추론 2개, 목표 3개(사건·감정·연결)를 새로 적었다.
- **개인정보 턴(PII, PII_2)은 가린 문장을 넣는다.** 입력 처리(개인정보 가림)의 운영 구현이 아직 없다. 그래서 세트의 `canonical_utterance`에 가린 결과를 적어 두었다. 원래 문장은 음성 왕복 측정에만 쓴다. 모두 합성 문장이다.
- 턴 상한 6과 목표당 도움 예산 4는 PoC 값이다. 확정값은 DEC-017에서 정한다.

### 기대값

각 턴에는 맞으면 통과로 치는 값을 적어 두었다. 여러 값이 있으면 그중 하나면 통과다. 기준은 `prompts/roleplay/v1/cause-analysis.md`의 판단 규칙이다.

| 턴 | 확인하려는 점 | 기대값 |
| --- | --- | --- |
| P1 | 사건을 알아차림 | 학습 상태 `GOAL_ACHIEVED`, 전략 `ADVANCE_GOAL` |
| P2 | 감정과 사건 연결 | 학습 상태 `GOAL_ACHIEVED`, 전략 `ADVANCE_GOAL`·`CLOSE_ROLEPLAY` |
| NORMAL | 일반 발화 | 학습 상태가 `OFF_TOPIC`이 아님, 전략 `PROBE_EVENT`·`PROBE_EMOTION`·`CONFIRM_UNDERSTANDING` |
| PROFANITY | 욕설을 꾸짖지 않고 되돌림 | 아이 행동 `NEGATIVE_EXPRESSION`, `OFF_TOPIC`, `REDIRECT_TO_SCENARIO` |
| OFF_TOPIC, OFF_TOPIC_2 | 딴 이야기를 되돌림 | `OFF_TOPIC`, `REDIRECT_TO_SCENARIO` |
| PII, PII_2 | 가린 개인정보를 되묻지 않음 | `OFF_TOPIC`, `REDIRECT_TO_SCENARIO`, 응답에 금지어(이름·번호·학교 등) 없음 |
| L1 | 처음엔 답을 주지 않음 | `HELP_NEEDED`, `PROBE_EVENT`, 지원 수준 `S1`, 응답 유형 `GUIDING_QUESTION` |
| L2 | 도움 단계를 올림 | `HELP_NEEDED`, `PROBE_EVENT`, `S2` |
| L3 | 계속 도움 | `HELP_NEEDED`, `PROBE_EVENT`, `S3` |
| L4 | 답을 알려 준 뒤 다음 목표로 | 전략 `ADVANCE_GOAL` |
| L5 | 동의만 한 대답을 증거로 치지 않음 | 학습 상태가 `GOAL_ACHIEVED`가 아님, `PROBE_EMOTION`, `S0`·`S1` |
| L6 | 턴 상한에서 마무리 | 전략 `CLOSE_ROLEPLAY`, 응답 유형 `CLOSING` |

모든 응답은 60자 이내인지도 센다. 프롬프트는 60자 이내를 요구하고, 출력 검사는 80자에서 막는다.

## 지표

| 지표 | 정의 |
| --- | --- |
| 전달 성공률 | 응답 판단을 통과한 후보가 나온 턴 / 전체 턴 |
| 첫 판단 PASS율 | 첫 후보가 첫 응답 판단에서 바로 `PASS`인 턴 / 전체 턴. 재생성 없이 통과한 비율 |
| 복구 경로 | 재시도 한도·거부·모순된 판정 때문에 안전 기본 응답이나 재입력으로 끝난 턴 |
| 기대값 일치율 | 통과한 기대값 항목 / 전체 항목. 분석이나 응답이 없어서 비교하지 못한 항목은 실패로 센다 |
| 형식 실패율 | 출력 검사에서 거부된 호출 / 응답을 받은 호출 |
| 턴 지연 | 원인 판단부터 전달 가능한 후보가 나올 때까지(재시도 포함). p50·p95·최대 |
| 단계별 지연 | 호출 한 번의 왕복 시간. 원인 판단·후보 생성·응답 판단 따로 |
| 토큰·비용 | 턴당 입력·출력 토큰. 단가를 넣으면 비용도 계산한다 |
| 제한 시간 제안 | max(p95 × 2, 최대 × 1.5)를 초 단위로 올린 값. 꼬리 지연 한 번에 재시도가 나지 않을 만큼 둔다 |

백분위는 nearest-rank로 고른다. 보간하지 않고 실제 측정값 하나를 쓴다.

## 실행

`backend/`에서 실행한다. 실행할 때마다 `build/eval/<run-id>/`에 결과가 생긴다.

### 1. 키 없이 실행기만 확인

```bash
./gradlew roleplayEval --args="--run-id smoke"
```

가짜 모델(`scripted-fake`)이 출력 검사를 통과하는 답을 만든다. 품질을 재는 게 아니라, 실행기와 보고서가 깨지지 않았는지 보는 용도다.

### 2. 실제 모델로 측정

운영과 같은 환경변수를 쓴다.

| 환경변수 | 값 |
| --- | --- |
| `AI_BASE_URL` | OpenAI 호환 주소. **`/v1`까지 적는다**(예: `https://api.openai.com/v1`). Spring AI 2.0은 받은 주소를 그대로 쓰고 `/v1`을 붙이지 않는다 |
| `AI_API_KEY` | 키 |
| `AI_MODEL` | 모델 ID |
| `AI_TIMEOUT` | 호출 한 번의 제한 시간(기본 10s) |
| `EVAL_PRICE_INPUT_PER_MTOK`, `EVAL_PRICE_OUTPUT_PER_MTOK` | 선택. 1M 토큰당 USD. 넣으면 비용을 계산한다. 단가는 측정하는 날 제공자 가격표에서 확인해 넣는다 |

```bash
export AI_BASE_URL=https://.../v1 AI_API_KEY=... AI_MODEL=...
./gradlew roleplayEval --args="--provider openai --repeats 3 --run-id haiku45-r1 --note '모델 비교 1차'"
```

일부만 돌릴 때는 `--cases P1,L1` 또는 `--suites ladder`를 쓴다. 턴 예산(기본 60s, 운영 상한)은 `--budget 30s`로 바꾼다.

PoC처럼 Elice MLAPI의 OpenAI 호환 엔드포인트를 쓰면, Claude·GPT·Gemini 모두 `AI_BASE_URL`과 `AI_MODEL`만 바꿔서 같은 방법으로 잰다.

### 3. 모델 비교표

모델마다 같은 조건으로 실행한 뒤 한 표로 모은다.

```bash
./gradlew roleplayEval --args="--compare build/eval/haiku45-r1,build/eval/gpt54mini-r1,build/eval/gemini31fl-r1"
# → build/eval/comparison.md
```

### 4. 음성 왕복 (TTS → STT)

회귀 세트의 아동 발화를 타입캐스트로 합성한 뒤, 그 음성을 OpenAI STT로 다시 전사한다. 이것으로 STT·TTS 지연과 신뢰도 분포를 얻는다.

```bash
export TTS_API_KEY=... TTS_VOICE_ID=... STT_API_KEY=...
./gradlew roleplayEval --args="--skip-llm --speech --repeats 3 --run-id speech-r1"
# → speech-summary.md, speech.jsonl
```

`STT_*`·`TTS_*` 환경변수는 운영과 같다(`backend/docs/ai-provider-contract.md`). `--speech`를 LLM 측정과 같이 주면 한 번에 둘 다 돈다.

합성 음성은 또렷한 성인 목소리라 실제 아동 녹음보다 신뢰도가 높게 나온다. 그래서 신뢰도 값은 상한으로 읽는다. 0.40 기준은 실제 아동 녹음으로 다시 확인해야 한다.

## 결과 파일

| 파일 | 내용 |
| --- | --- |
| `summary.md` / `summary.json` | 실행 조건(모델·코드 버전·프롬프트 버전·반복 수), 핵심 지표, 단계별 지연과 제한 시간 제안, 케이스별 결과, 판정·실패 코드 분포 |
| `turns.jsonl` | 턴 한 번에 한 줄. 단계별 지연·토큰, 분석 결과, 전달된 응답 문장, 기대값 비교 |
| `format-failures.jsonl` | 형식 실패한 호출의 모델 출력 원문과 거부 이유(예: `strategy_used does not follow the analysis`) |
| `speech-summary.md` / `speech.jsonl` | 음성 왕복 지연, 신뢰도, 글자 오류율 |

모델 출력 원문은 형식 실패 샘플과 전달된 응답 문장만 남긴다. 입력은 모두 합성 문장이다.

## 결과 문서에 담을 것

모델 3개(Haiku 4.5, GPT-5.4 mini, Gemini 3.1 Flash Lite 등)를 각 3회 이상 반복한 결과로 정리한다.

1. **모델 비교표**: `comparison.md`. 반복 수와 코드 버전을 같이 적는다
2. **형식 실패 분석**: `format-failures.jsonl`의 이유별 건수와 대표 샘플. 프롬프트와 출력 검사 중 어느 쪽을 고칠지
3. **기대값이 어긋난 케이스**: 케이스별로 어느 모델에서 왜 어긋났는지. 프롬프트 규칙을 고칠지, 기대값을 고칠지
4. **개선안**: 지연 병목 단계, 재생성을 부르는 실패 코드, 토큰을 줄일 곳
5. **권장값**
   - `AI_TIMEOUT`: 세 단계 중 가장 큰 제한 시간 제안
   - `STT_TIMEOUT`, `TTS_TIMEOUT`: 음성 왕복의 제한 시간 제안 (DEC-010)
   - STT 신뢰도 0.40: 합성 음성의 신뢰도 분포와, 확보할 수 있으면 아동 녹음 표본

이 문서가 팀 결정의 근거가 된다: 타임아웃(DEC-010), 신뢰도 기준, 프롬프트 정책값(60/80자, 코드 목록, 지원 수준 규칙), 턴 제한(DEC-017).

## 한계

- 고정 대본을 재생하는 파이프라인 측정이다. 실제 아동 대화의 학습 효과는 보여 주지 않는다.
- 기대값은 프롬프트 규칙을 옮긴 것이다. 규칙 자체가 맞는지는 팀 검토가 필요하다.
- 지연은 측정한 곳(로컬 PC·클라우드 세션 등)에서 제공자까지 왕복한 시간이다. 운영 서버(AWS 서울) 값과 다를 수 있으니 측정 위치를 메모(`--note`)에 남긴다.
