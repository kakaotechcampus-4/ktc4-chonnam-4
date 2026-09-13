
# 느링고 역할극 PoC · 모델 비교 기준선

## 공통 비교 단위 — 14턴

세 모델에 공통으로 들어 있는 `SCN-GEN-16DE81D6`의 아래 14턴을 비교 단위로 쓴다.

| 스위트 | 고정 프로브 | 턴 |
| --- | --- | ---: |
| `play` | `P1`, `P2` | 2 |
| `safety` | `NORMAL`, `PROFANITY`, `OFF_TOPIC`, `OFF_TOPIC_2`, `PII`, `PII_2` | 6 |
| `ladder` | `L1` ~ `L6` | 6 |
| **합계** |  | **14** |

- 시나리오: **발표하다가 말을 못 해서 앉은 친구** (`SCN-GEN-16DE81D6`)
- 턴 상한: 6 (기존 세 모델 실행에 사용된 시나리오 DB 값)
- 목표당 도움 예산: 4턴
- 아동 발화: LLM 생성이 아닌 `server/scripts/utterances.py`의 고정 대본
- 바꾸는 값: 에이전트 모델과 해당 모델이 배포된 Elice 엔드포인트
- 고정하는 값: 시나리오·발화 순서·프롬프트·`MAX_OUTPUT_TOKENS=2048`·후보 최대 3회·수리 재시도 1회·Moderation 모델

## 모델별 원자료

| 모델 | 회차 / 출처 | 공통 14턴 포함 | Moderation | 상태 |
| --- | --- | --- | --- | --- |
| `claude-haiku-4-5` | `T12-fix1` (`t12-measurements.md`) | 예 — 28턴 중 공통 14턴만 사용 | `omni-moderation-latest` | 비교 가능 |
| `gpt-5.4-mini` | `20260911T152020Z` (`summary.md`) | 예 — 14턴 | `omni-moderation-latest` | 비교 가능 |
| `gemini-3.1-flash-lite` | `gemini31flashlite-2` | 예 — 공통 14턴으로 재집계 | `omni-moderation-latest` | 비교 가능 |

`T12-fix1`은 28턴으로 실행됐지만, 모델 비교에서는 공통 14턴 뒤에 붙은 `safety` 추가 6턴, `halt` 2턴, `ladder` 추가 6턴을 **전부 제외한다**. Haiku의 `server/runs/T12-fix1/turns.jsonl`에서 공통 14턴만 골라 같은 지표를 집계한다.

## 현재 관찰값

| 모델 | 기록 단위 | 전체 턴 p50 | 전체 턴 p95 | 토큰 합계 | 비고 |
| --- | ---: | ---: | ---: | ---: | --- |
| `claude-haiku-4-5` | 공통 14턴 | 원자료에서 재집계 | 원자료에서 재집계 | 원자료에서 재집계 | `T12-fix1`의 나머지 14턴 제외 |
| `gpt-5.4-mini` | 공통 14턴 | 6,985ms | 15,422ms | 118,258 | Moderation 포함, 모든 턴 OK |
| `gemini-3.1-flash-lite` | 공통 14턴 | 4,609ms | 9,062ms | 98,092 | Moderation 포함, 모든 공통 턴 OK |

Gemini는 `play`에서 P3까지 진행해 원본 실행은 15턴이다. 비교 집계에서는 세 모델이 공유하는 P1·P2만 남기고 P3은 제외했다. 같은 14턴에서 GPT보다 짧은 응답 시간을 보였지만, 각 모델 1회 측정 결과이므로 최종 우열은 반복 측정 뒤에 판단한다.


