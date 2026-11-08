# 역할극 계약 축소와 재분석 개선 결과

기준: 공식 develop cac1fa8. 작업 브랜치: refactor/roleplay-latency. 실제 Elice 호출과 Dev 배포는 아직 수행하지 않았다.

## 변경한 동작

- 캐릭터 분석·검수가 같은 비순차 목표/도움 제안/부분 이해 정책을 사용한다. 현재 목표가 미달성이라는 이유만으로 다음 질문 초점을 거절하지 않는다.
- GOAL_SKIPPED에는 실제 달성 주장 또는 부당한 마무리의 출처를 요구한다. 분석에 그런 판단이 없거나 후보 인용이 실제 대사에 없으면 검수 출력을 거절한다. 후보 문장의 의미 판단 자체는 독립 검수에 남는다.
- 거절 영역·세부 사유·수정 경로를 내부 결과에서 AnalysisRetry.review_issues까지 보존한다. 짧은 설명은 비신뢰 검수 자료이며 명령으로 실행하지 않는다. 이 정보는 내부 실행 중에만 사용하고 기존 HTTP/저장용 EvaluationResult JSON에는 추가하지 않는다.
- 캐릭터 분석의 모델 출력은 관찰별 o키와 상태를 반환한다. 서버가 요청에 고정된 turn_id·analysis_id·기준 참조·내부 버전을 복원하고 기존 분석/지원 보정 검사를 수행한다. 누락·추가 키, 승인 밖 목표, 잘못된 근거는 거절한다.
- 캐릭터 생성의 모델 출력은 text·mascot_hint뿐이다. 서버가 후보 ID·턴·목표·전략·지원·유형·이전 실패 ID를 구성한다. 대사의 실제 역할·지원 수준·안전은 독립 검수 대상으로 유지한다.
- 승인 기준은 approved_criteria에 보존하고 모델 입력의 중복 required_evidence·검수 항목의 반복 설명을 줄인다. 서버의 원본·참조 바인딩·확정 관찰·도움 이력은 유지한다.
- 캐릭터 전용 지침을 사용해 안내자 문답 규칙에 예외를 덧붙이던 중복을 줄였다. 정상 호출 순서·횟수, 공유 60초, SDK 재시도 0은 유지한다.

## 서버와 평가 실행기 연결

서버의 RoleplayHttpConfiguration과 roleplayEval 기본값은 간결한 캐릭터 계약을 선택한다. 안내자 경로는 그대로다. 과거 진단 재생과 비교를 위해 기존 2인자 RoleplayPromptFactory 생성자는 기존 출력 형태를 유지하며, 실제 서버는 3인자 생성자로 간결 계약을 명시한다.

| 단계 | 기본 캐릭터 prompt | 모델 출력 schema |
|---|---|---|
| 분석 | roleplay-cause-analysis-character/v1 | character-analysis-output/v1 |
| 생성 | roleplay-response-generation-character/v3 | character-candidate-output/v1 |
| 검수 | roleplay-response-evaluation-character/v10 | evaluation-output/v7 |

내부 analysis-result/v2·candidate-response/v1·evaluation-result/v2와 외부 HTTP·DB 구조는 유지한다. 프론트엔드 후속 변경은 없다. 모델 원문은 이제 간결한 wire이므로 분석 원문의 o키는 같은 요청 MODEL_REQUEST의 analysis_observation_tasks와 approved_criteria를 함께 보아야 한다.

## 크기 비교

동일한 합성 입력에서 공통 정책을 적용한 legacy 형태와 compact 형태를 비교했다. 시스템 지침+사용자 JSON+API JSON Schema의 Java 문자열 길이를 합산한다. Unicode 토큰 수·네트워크 바이트·추론 토큰이나 실제 지연 측정이 아니다. 스키마를 API로 옮긴 비용도 합계에 포함한다.

| 단계 | 기존 형태 요청 전체 | 간결 형태 요청 전체 | 감소 |
|---|---:|---:|---:|
| 분석 | 18,326자 | 10,474자 | 42.8% |
| 생성 | 9,191자 | 4,756자 | 48.3% |
| 검수 | 27,564자 | 17,322자 | 37.2% |

같은 판단의 분석 출력은 1,306→542자(58.5%), 같은 대사의 생성 출력은 330→42자(87.3%)로 감소했다. 최종 지침 문구를 반영한 RoleplayLatencyContractTest의 PAYLOAD_SIZE/OUTPUT_SIZE 출력으로 집계했다.

이는 계약·지침·중복 입력을 함께 바꾼 묶음의 비교다. 각 변경의 독립적인 시간 효과나 제공자 토큰 절감률로 해석하지 않는다. 실제 모델이 최적화된 요청을 빠르고 정확하게 처리하는지는 별도 검증해야 한다.

## 로컬 검증 범위

- 요청별 기준 바인딩, 누락/추가 관찰 키·상태·서버 소유 필드 거절.
- 승인 밖 목표·활동 근거, 잘못된 지원 변경을 복원 후 기존 업무 검사로 거절.
- 생성의 길이·질문 수·필수 마스코트 검사와 S3 대사의 독립 검수 유지.
- 정상 초점 변경과 실제 후보의 조기 마무리 구분, 잘못된 출처·인용 거절.
- 재분석 사유가 다음 분석에 전달되고 기존 공개 결과 JSON에 노출되지 않음.
- 입력 투영 후 승인 기준·확정 도움·과거 근거·검수 바인딩 보존.
- 새 schema와 프롬프트를 사용하는 합성 제공자 평가 실행기 연결.

합성 제공자 smoke의 DELIVERED는 실행 경로 확인이다. 이 가짜 제공자는 실제 P1 학습 정답을 구현하지 않아 기대값은 일부만 일치할 수 있다. 그 실행의 ms·가짜 토큰·PASS를 실제 모델 성능이나 교육적 품질의 근거로 사용하지 않는다.

- 전체 `./gradlew --no-daemon build roleplayEval ... --output-mode legacy` 통과(4분 46초). JUnit XML 합계 2,901개: 통과 2,895, 실패·오류 0, 실행 조건이 필요한 6개 건너뜀(실제 ML API 1개, FFmpeg 실환경 5개).
- 이후 현재 목표의 모든 기준 충족·과거 판정 불가와 현재 입력 구분·S2 표현을 지침에서 명확히 했다. Java 코드 변경 없이 관련 25개 검사와 최종 리소스의 `build -x test` 패키징/정적 검사가 통과했다. 전체 Java 회귀 결과는 재사용했다.
- compact/legacy 합성 제공자 smoke 모두 분석·생성·검수 3회, 형식 실패 0, DELIVERED로 마쳤다. 두 실행 모두 가짜 제공자의 기대값은 1/3이다. 실제 학습 품질·속도의 개선 증거로 사용하지 않는다.
- 마이그레이션 가드(Bash 5.2 컨테이너), 공개 금지 파일·값 검사, diff 공백 검사 통과. 실제 키·로컬 설정·build/eval 결과는 커밋하지 않는다.
- 전체 검사 집계는 로컬 build/reports/roleplay-latency-validation/full-test-summary.json에 보존했다. 최종 지침 검사 집계는 같은 폴더의 final-prompt-test-summary.json이다. 이 파일들은 공개 소스에 포함하지 않는다.

최종 리소스의 패키징·정적 검사도 통과했다. Gitleaks v8.30.1로 upstream/develop 이후 이번 작업 2개 커밋을 검사해 비밀값 검출이 없음을 확인했다.

## 승인 후 실제 비교 방법

IntelliJ Gradle 프로젝트는 backend다. 기존 .env.local의 모델·주소·키·추론 설정은 그대로 사용한다. 실행마다 별도 결과 폴더가 생성된다. 아래 명령은 유료 제공자를 사용하므로 승인 후 실행한다.

동일 정책의 기존 출력 형태:

```text
roleplayEval --args="--provider openai --set eval/roleplay-regression-character-p1-v1.json --cases P1 --repeats 1 --budget 60s --diagnostics --output-mode legacy"
```

간결한 출력 형태:

```text
roleplayEval --args="--provider openai --set eval/roleplay-regression-character-p1-v1.json --cases P1 --repeats 1 --budget 60s --diagnostics --output-mode compact"
```

--output-mode를 생략하면 compact다. 이 비교의 legacy는 cac1fa8 바이너리 재실행이 아니라 이번 공통 정책이 적용된 기존 출력 형태다. 모델과 코드·목표·지원 이력·프롬프트 버전을 함께 기록한다. 단건 비교로 p95나 일관된 개선을 확정하지 않는다.

먼저 실제 제공자의 새 schema 수용 여부, 정상 응답·첫 검수·재분석 여부, 단계별 토큰과 시간을 확인한다. 잘못된 후보를 현재 compact 검수에 넣는 반례 검사도 별도로 준비해 수행한다. 로컬 실행기는 입력 판정·TTS·DB 저장을 포함하지 않으므로 이후 Dev에서 HTTP 전체 지연을 확인해야 한다.

실제 비교 전에는 속도 개선 완료나 10초 달성을 주장하지 않는다. 필요하면 그 결과를 기준으로 단계별 모델·추론 설정 비교를 설계한다.
