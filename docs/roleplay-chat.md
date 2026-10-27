# 역할극 대화 도구 (roleplayChat)

시나리오를 하나 만들고, 사람이 아동 역할로 한 턴씩 입력하며 AI 응답과 판단을 직접 보는 개발용 도구다. 화면·API 가 아직 AI 와 연결되지 않아서, 프롬프트가 실제로 어떻게 대답하는지 사람이 대화로 확인하려고 만들었다.

## 무엇을 운영과 같게, 무엇을 다르게 하나

| 운영과 같음 | 이 도구만의 것 (운영 아님) |
| --- | --- |
| 시나리오 생성 프롬프트·계약 검사 (`scenario-generation/v1`) | 시나리오 생성 재시도: 검사 실패 시 실패 코드를 붙여 최대 3번 |
| 한 턴 처리: 원인 분석 → 응답 생성 → 응답 평가, 재시도 (`RetryingRoleplayTurnExecutor`) | 턴 사이 상태 갱신(아래 규칙). 운영 오케스트레이터가 아직 없다 |
| 출력 검사(형식·길이·안전 낱말) | 입력 처리(개인정보 가림·안전 처리)·STT·TTS 는 거치지 않는다. 입력한 글이 그대로 표준 발화가 된다 |

턴 사이 상태 규칙:

- 원인 분석이 `GOAL_ACHIEVED` 면 현재 목표를 `ACHIEVED`, 정답 공개 응답이면 `ANSWER_REVEALED`, 아니면 `IN_PROGRESS`.
- 다음 지원 단계는 실제로 전달된 응답의 `support_level`.
- 응답이 다른 목표를 겨냥했거나 현재 목표가 끝났으면 그 목표(끝났으면 다음 남은 목표)로 옮기고 지원 횟수를 새로 센다.
- `CLOSING` 응답, 모든 목표 종료, 턴 상한(기본 8) 중 하나면 끝.

그래서 목표 전환·지원 단계 변화는 "모델이 이렇게 판단했다"를 보는 용도이고, 운영 동작을 확인하는 것이 아니다.

## 준비

`backend/.env.local` 에 `AI_BASE_URL`·`AI_API_KEY`·`AI_MODEL` 이 있으면 된다([ai-quality-measurement.md](ai-quality-measurement.md)의 `.env.local` 참고). 시나리오 생성이 7초 안팎 걸리므로 `AI_TIMEOUT=30s` 를 권한다. 비용을 보려면 `EVAL_PRICE_INPUT_PER_MTOK`·`EVAL_PRICE_OUTPUT_PER_MTOK` 도 넣는다.

## 실행

```bash
cd backend
./gradlew -q --console=plain roleplayChat
```

Windows PowerShell:

```powershell
chcp 65001
cd backend
.\gradlew.bat -q --console=plain roleplayChat
```

- `chcp 65001` 을 하지 않으면 입력한 한글이 깨진다. 깨지면 도구가 알려 준다.
- `-q --console=plain` 을 빼면 Gradle 진행 막대가 입력 줄을 덮는다.
- 옵션은 `--args` 로 넘긴다. PowerShell 은 `'--args=--case L1_PLAYGROUND --support S1'` 처럼 작은따옴표로 감싼다.

| 옵션 | 뜻 |
| --- | --- |
| `--case L1_PLAYGROUND` | 강사 입력을 묻지 않고 시나리오 평가 세트(`scenario-generation-v1.json`)의 입력을 쓴다 |
| `--level L1\|L2\|L3` | 난이도 |
| `--support S0\|S1\|S2` | 아동의 초기 지원 수준(첫 질문이 달라진다) |
| `--max-turns 8`, `--max-support-turns 3` | 턴 상한 |
| `--judgement off` | 모델 판단을 숨기고 아동이 보는 대화만 본다 |
| `--provider scripted` | 키 없이 흐름만 보는 가짜 모델 |

## 대화 화면

```
AI > 친구가 무릎을 만지고 표정을 찡그려. 무슨 일이 있었을까?
나(아동) > 친구가 넘어졌어
AI > 친구가 넘어졌구나. 그때 친구 얼굴은 어땠어?
    └ 판단: MG-01 · 학습 상태 GOAL_ACHIEVED · 빈 곳 NONE · 전략 ADVANCE_GOAL → GUIDING_QUESTION(S1, MG-02)
    └ 목표: MG-01 ACHIEVED · MG-02 NOT_OBSERVED · MG-03 NOT_OBSERVED · 3번 호출 5.5s
```

- 판단 줄: 이번 발화를 어느 목표로 봤는지, 원인 분석의 학습 상태·빈 곳·다음 전략, 실제 응답의 유형(지원 단계, 겨냥한 목표).
- 대화 중 명령: `/상태`, `/시나리오`, `/판단`(보이기·숨기기), `/끝`.
- 전달하지 못한 턴(재시도 한도·시간 초과)은 대화에 남기지 않는다. 같은 말을 다시 해 볼 수 있다.
- 끝나면 `build/eval/chat/chat-<시각>.md` 에 시나리오·대화·판단이 남는다. 프롬프트 개선 근거로 팀에 공유할 때 쓴다. 입력한 말이 그대로 남으니 실제 아동 정보는 넣지 않는다.

비용은 한 턴에 LLM 3번(재시도가 있으면 더) 호출이라 gpt-5.4-nano 기준 한 턴 약 $0.002–0.003 이다.
