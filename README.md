<h1 align="center">🐢 느링고 (Neuringo) 🐰</h1>

<div align="center">

<b>카카오테크 캠퍼스 4기 2단계 팀 프로젝트 · 전남대 4팀</b>
<br><br>
<b><i>천천히, 그래도 분명하게. 마음을 읽는 연습</i></b>
<br><br>
<b>느린 학습자를 위한 AI 감정 인식·공감 역할극 서비스</b>

<br><br>

![Java](https://img.shields.io/badge/Java%2021-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot%204.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Spring AI](https://img.shields.io/badge/Spring%20AI%202.0-6DB33F?style=flat-square&logo=spring&logoColor=white)
![React](https://img.shields.io/badge/React%2019-61DAFB?style=flat-square&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript%206-3178C6?style=flat-square&logo=typescript&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2018-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![AWS](https://img.shields.io/badge/AWS-232F3E?style=flat-square&logo=amazonwebservices&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-844FBA?style=flat-square&logo=terraform&logoColor=white)

<br><br>

<b>🌐 서비스 바로가기:</b> <a href="https://d430pejtvizp8.cloudfront.net/" target="_blank"><b>https://d430pejtvizp8.cloudfront.net</b></a>
<br>
<sub>태블릿·PC·모바일 웹 · 카메라·마이크 권한 필요 · 국내에서만 접속 가능</sub>

<br><br>

<img src="docs/images/readme/landing.png" width="700" alt="느링고 시작 화면">

</div>

---

<div align="center">

## 📖 목차

| 항목 |
|------|
| [🎨 소개](#-소개) |
| [🌟 주요 기능](#-주요-기능) |
| [🤖 AI 파이프라인](#-ai-파이프라인) |
| [🎯 품질 목표와 설계 결정](#-품질-목표와-설계-결정) |
| [💻 기술 스택](#-기술-스택) |
| [🛠 시스템 아키텍처](#-시스템-아키텍처) |
| [🚀 CI/CD](#-cicd) |
| [📈 에이전트 실행 대시보드](#-에이전트-실행-대시보드) |
| [🧪 테스트 전략](#-테스트-전략) |
| [📊 측정 지표](#-측정-지표) |
| [🗂 디렉터리 구조](#-디렉터리-구조) |
| [⚙️ 로컬 실행](#️-로컬-실행) |
| [📚 문서 모음](#-문서-모음) |
| [👥 팀원](#-팀원) |

</div>

---

## 🎨 소개

> *"친구가 왜 울고 있을까?" 그 질문에 스스로 답할 수 있을 때까지, 느링고가 옆에서 기다려 줍니다.*

**느링고**는 경계선 지능·발달지연 등 **느린 학습자 아동**이 상대의 감정을 알아차리고 공감하는 말을 연습하도록 돕는 웹 서비스입니다.

### 배경

느린 학습자 아동을 지도하는 강사는 감정 표현·사회적 의사소통 수업에서 이런 어려움을 겪습니다.

- 정서 인식·표현 훈련에는 **반복적이고 안전한 연습 환경**이 필요하지만, 기존 교육 콘텐츠는 이를 충분히 제공하지 못합니다.
- 수업이 감정·상황 카드 같은 정적 도구 위주라, **실제 대화처럼 반복 연습**시킬 방법이 마땅치 않습니다.
- 아동별 역할극 상황과 질문을 **매 수업 직접 준비**해야 합니다.
- 수업 중 아동의 표현·도움 수준을 실시간으로 기록하기 어려워, **다음 수업 방향을 정하는 데** 많은 시간이 듭니다.

### 느링고가 하는 일

| 대상 | 느링고가 하는 일 |
|---|---|
| 👩‍🏫 **강사** | 학습 목표만 정하면 AI가 아동 수준(L1–L3)에 맞는 역할극 시나리오를 만듭니다. 강사가 **검토·승인한 시나리오만** 아동에게 배정되고, 활동이 끝나면 근거가 달린 학습 리포트와 다음 활동 추천을 한 곳에서 봅니다. |
| 🧒 **아동** | 4자리 코드로 쉽게 들어와 **표정 퀴즈**로 감정을 익히고, **역할극**에서 캐릭터와 말이나 글로 대화합니다. 막히면 토끼 **깡총이**가 단계별 힌트를 줍니다. 활동을 끝까지 마치면 스탬프를 받습니다. |

### 우리가 지킨 원칙

- **AI가 만든 것은 확인을 거친 뒤에만 아동에게 갑니다.** 시나리오는 강사 승인을, 역할극 대사는 별도의 AI 검수를 통과해야 전달됩니다.
- **AI가 아동을 진단하거나 학습 방향을 정하지 않습니다.** AI는 추천과 근거만 보여 주고, 결정은 강사가 합니다.
- **기술 오류는 아동의 실패로 세지 않습니다.** 음성 인식이 불확실한 턴은 점수에서 뺍니다.
- **개인정보를 밖으로 보내지 않습니다.** 아동은 이름만 저장하고, AI에 보내기 전에 개인정보를 가리며, 카메라 영상은 서버로 보내지 않습니다.

---

## 🌟 주요 기능

### 👩‍🏫 강사 서비스

#### ✨ 회원·학급·아동 관리
- 회원가입(약관 동의), 로그인, 로그아웃, 탈퇴를 지원합니다. 강사는 **자기 학급과 아동만** 볼 수 있습니다.
- 학급을 만들고 아동을 등록한 뒤, 아동마다 **입장 코드**(숫자 4자리, 24시간 유효)를 발급합니다.

#### ✨ 활동 만들기
- **대상 → 유형 → 목표 → 추가 조건 → 확인**, 5단계로 활동을 만듭니다. 학습 목표는 목록에서 고르거나 직접 입력할 수 있습니다.
- 첫 활동은 표정 퀴즈와 역할극을 함께 배정하고, **퀴즈 정답률로 첫 역할극 난이도**를 정합니다. (50% 미만 L1, 80% 미만 L2, 그 이상 L3)

#### ✨ AI 시나리오 생성과 승인
- AI가 **상황, 등장인물, 작은 목표, 첫 질문**을 담은 시나리오를 만듭니다. 난이도(L1–L3)에 따라 작은 목표가 3–4개로 달라집니다.
- 조건에 맞는 **승인된 시나리오가 있으면 먼저 보여 주고**, 없으면 새로 만듭니다. 강사 검토 부담과 비용을 줄이기 위해서입니다.
- 강사는 후보를 넘기거나, 고친 뒤 승인할 수 있고, 승인 대기함에서 한꺼번에 확인합니다.

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/readme/teacher-scenario-review.png" width="400" alt="시나리오 검토"><br>시나리오 검토<br><sub>상황·인물·고정 사실·작은 목표·첫 질문 확인</sub></td>
    <td align="center"><img src="docs/images/readme/teacher-scenario-approve.png" width="400" alt="시나리오 승인"><br>승인 확인<br><sub>검토 확인 후 승인하면 활동에 배정</sub></td>
  </tr>
</table>
</div>

#### ✨ 활동 관리와 리포트
- 활동별 진행 상태를 관리하고, 퀴즈·역할극 **활동 리포트**를 봅니다.
- 역할극이 끝나면 점수와 추천을 먼저 저장하고, **AI가 리포트를 쓰고 별도의 검토 단계에서 확인하는 과정을 최대 3번** 반복합니다. 통과한 리포트만 강사에게 보입니다.

#### ✨ 다음 활동 추천
- **보완 · 반복 · 적용 · 확장** 중 최대 3개를 근거와 함께 추천합니다.
- 강사는 추천을 **채택 · 수정 채택 · 보류 · 거절**할 수 있습니다. 채택해도 자동으로 배정되지 않고, 강사가 직접 활동을 만듭니다.
- 다음 역할극 난이도는 점수율이 50% 미만이면 한 단계 내리고, 80% 이상을 2번 연속 달성하면 올립니다.

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/readme/teacher-dashboard.png" width="400" alt="강사 대시보드"><br>대시보드</td>
    <td align="center"><img src="docs/images/readme/teacher-classroom-detail.png" width="400" alt="학급 상세"><br>학급 상세</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/readme/teacher-activities.png" width="400" alt="활동 관리"><br>활동 관리</td>
    <td align="center"><img src="docs/images/readme/teacher-quiz-report.png" width="400" alt="퀴즈 리포트"><br>활동 리포트 (표정 퀴즈)</td>
  </tr>
</table>
</div>

### 🧒 아동 서비스

> **코드 입력 → 오늘의 활동 → 표정 퀴즈 → 역할극 → 스탬프**

캐릭터의 역할을 고정해 아동이 헷갈리지 않게 했습니다. **🐢 거북이는 질문·안내**, **🐰 토끼 깡총이는 힌트·도움**을 맡습니다. 한 화면에는 한 가지 행동만 두고, 음성과 글자를 함께 보여 줍니다.

#### ✨ 코드로 입장
- 강사가 준 4자리 코드만 입력하면 바로 오늘의 활동으로 들어갑니다. 여러 번 틀리면 일정 시간 막힙니다.

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/readme/child-access.png" width="400" alt="코드 입력"><br>코드 입력</td>
    <td align="center"><img src="docs/images/readme/child-hello.png" width="400" alt="인사"><br>오늘의 활동</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/readme/child-activities.png" width="400" alt="내 활동"><br>내 활동</td>
    <td align="center"><img src="docs/images/readme/child-activity-intro.png" width="400" alt="활동 소개"><br>활동 소개 (퀴즈 → 역할극 → 스탬프)</td>
  </tr>
</table>
</div>

#### ✨ 표정 퀴즈
- **사진·그림 보고 감정 고르기**: 표정을 보고 어떤 감정인지 고릅니다.
- **카메라로 표정 지어 보기**: 상황을 듣고 그때의 표정을 직접 지어 봅니다. 표정 인식은 [face-api](https://github.com/vladmandic/face-api)로 **브라우저 안에서만** 처리하며, 얼굴 영상은 서버로 보내지 않습니다.
- 틀리면 **힌트 2단계**를 받고 다시 도전합니다.

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/readme/child-quiz-camera.png" width="400" alt="카메라로 표정 짓기"><br>카메라로 표정 지어 보기</td>
    <td align="center"><img src="docs/images/readme/child-quiz-photo.png" width="400" alt="상황 보고 감정 고르기"><br>상황 보고 친구 감정 고르기</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/readme/child-quiz-hint.png" width="400" alt="깡총이 힌트"><br>틀리면 깡총이 힌트</td>
    <td align="center"><img src="docs/images/readme/child-quiz-correct.png" width="400" alt="정답"><br>정답 피드백</td>
  </tr>
</table>
<img src="docs/images/readme/child-quiz-done.png" width="500" alt="퀴즈 완료">
<br>퀴즈 완료 → 역할극으로
</div>

#### ✨ AI 역할극
- 깡총이가 상대를 소개하면 역할극이 시작되고, 아동은 **말이나 글로** 대답합니다. 상대 캐릭터의 대사는 음성으로 재생됩니다.
- 음성은 브라우저에서 최대 30초까지 녹음해 서버로 보냅니다. 음성 인식이 안 되면 다시 말해 달라고 하고, **3번째부터는 글자 입력**을 권합니다.
- 막히면 도움을 한 단계씩 늘립니다: **열린 질문 → 단서 주기 → 두 개 중 고르기 → 정답 알려 주기**. 힌트 없이 연속으로 잘하면 다음 목표는 한 단계 낮춰서 시작합니다.
- 작은 목표를 모두 달성하거나 최대 턴에 도달하면 역할극이 끝나고, 스탬프를 받습니다.

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/readme/child-roleplay-start.png" width="400" alt="역할극 시작"><br>역할극 시작 — 말이나 글로 대답</td>
    <td align="center"><img src="docs/images/readme/child-roleplay-hint.png" width="400" alt="깡총이 도움"><br>막히면 깡총이가 도와줘요</td>
  </tr>
</table>
<img src="docs/images/readme/child-roleplay-chat.png" width="600" alt="역할극 대화">
<br>감정 → 이유 → 위로하는 말 순서로 대화가 이어집니다
</div>

---

## 🤖 AI 파이프라인

### 역할극 한 턴

```mermaid
flowchart LR
  A["🎙 아동 음성"] --> STT["음성 인식 STT"]
  STT --> PII["개인정보 가리기"]
  PII --> IN["① 입력 검사<br/>분석할 수 있는 말인지 · 위험한 말인지"]
  IN --> AN["② 분석<br/>달성한 목표 · 다음 도움 단계"]
  AN --> GEN["③ 대사 생성<br/>상대 캐릭터 대사 + 깡총이 힌트"]
  GEN --> REV{"④ 검수<br/>아이에게 보내도 되는가"}
  REV -- "통과" --> TTS["음성 합성 TTS"] --> C["🐢 화면·음성"]
  REV -- "실패 (최대 3번 재생성)" --> GEN
  REV -. "3번 모두 실패" .-> F["미리 정해 둔 안내 문구"]
```

- 정상적인 경우 한 턴에 **AI를 4번** 부릅니다.
- 시간 제한: **LLM 10초, STT·TTS 15초, 한 턴 전체 60초**. 시간을 넘기면 안내 문구로 마무리합니다.
- 같은 요청이 두 번 와도 한 번만 처리하고(멱등 키), 턴마다 진행 상태를 저장해 새로고침·재접속 후에도 이어서 합니다.

> **응답 속도 개선** — 검수가 전체 시간의 절반 넘게 차지하지만 품질을 위해 빼지 않았습니다. 대신 AI가 내보내는 내용을 줄여(요청 크기 37–48% 감소, 생성 출력 330자 → 42자) 한 턴 **27초 → 17초**로 줄였습니다. ([결과 문서](backend/docs/roleplay-latency-improvement-results-2026-11-09.md))

### 사용 모델

| 용도 | 모델 | 비고 |
|---|---|---|
| 역할극 · 입력 검사 · 시나리오 생성 | google/gemini-3.8-flash | 엘리스 ML API(OpenAI 호환)로 호출. Spring AI로 연결해 주소와 모델 이름만 바꿔 교체 가능 |
| 리포트 작성 | google/gemini-3.8-flash | |
| 리포트 검토 | google/gemini-3.8-flash | 작성과 검토를 다른 프롬프트로 분리 |
| 음성 인식 (STT) | OpenAI gpt-4o-mini-transcribe | 인식 신뢰도가 낮으면 다시 말해 달라고 합니다 |
| 음성 합성 (TTS) | 타입캐스트 ssfm-v30 / OpenAI gpt-4o-mini-tts | 설정으로 선택 |
| 표정 인식 | face-api (TensorFlow.js) | 브라우저에서 실행, 서버 호출 없음 |

프롬프트는 [백엔드 코드 안에 버전별 파일](backend/src/main/resources/prompts)로 관리하고, 결과를 저장할 때 어떤 버전을 썼는지 함께 남깁니다.

### 학습 리포트 작성·검토 루프

```mermaid
flowchart LR
  E["활동 기록 → 근거 E1, E2…"] --> D["초안 작성<br/>gemini-3.8-flash"]
  D --> R{"코드 규칙<br/>근거 ID · 칸 배치 · 진단 낱말 · 길이"}
  R -- "위반" --> D
  R -- "통과" --> V{"검토 모델<br/>gemini-3.8-flash"}
  V -- "고칠 점 전달" --> D
  V -- "통과" --> OK["✅ 강사에게 표시"]
```

- 모든 문장에 실제 기록 근거(E1, E2…)를 달아야 하고, "장애", "산만" 같은 진단·단정 표현은 막습니다.
- 코드 규칙을 먼저 돌려, 규칙에 걸리면 검토 모델을 부르지 않습니다(비용 절감).
- 최대 3번 안에 통과하지 못한 리포트는 보이지 않고, 정량 지표만 남습니다.

---

## 🎯 품질 목표와 설계 결정

### 품질 목표 우선순위

1. **아동 안전과 교육적 정확성** — 부적절하거나 잘못된 내용을 보여 주지 않고, 아동의 말을 학습 목표에 맞게 이해한다.
2. **대화 중단 최소화** — 장애가 나도 아이가 최대한 느끼지 못하게 한다.
3. **장애 복구 가능성** — 중간에 끊겨도 원래 상태로 돌아온다.
4. **응답 시간** — 너무 느리면 대화가 어색해진다.
5. **개인정보 보호**
6. **운영 비용**

### 품질 시나리오

| 품질 목표 | 상황 | 대응 |
|---|---|---|
| 안전·교육적 정확성 | AI가 시나리오와 안 맞거나 부적절한 대사를 만듦 | 검수를 통과한 대사만 전달. 실패하면 최대 3번 다시 만들고, 그래도 안 되면 정해 둔 안내 문구 |
| 대화 중단 최소화 | 외부 AI API가 느리거나 실패함 | 한 턴 60초 제한, 음성 인식 실패 시 재요청 → 3번째부터 글자 입력 권유 |
| 장애 복구 | 중복 요청, 새로고침, 서버 재시작 | 멱등 키, 턴별 상태 저장, 매일 DB 백업 |
| 개인정보 보호 | 아동 음성·대화가 외부 AI로 나감 | AI 호출 전 이름·전화번호 가리기, 카메라 영상 미전송, 로그의 아동 이름 자동 검사 |
| 운영 비용 | 호출·재시도가 쌓여 비용 증가 | 용도별 모델 분리, 턴당 비용 측정, 30분 미사용 시 서버 자동 종료 |

### 트레이드오프

| 선택지 | 결정 | 이유 |
|---|---|---|
| 응답 품질 vs 속도 | **품질 우선** | 검수를 빼지 않고, 대신 AI 출력량을 줄여 27초 → 17초 |
| AI 자동화 vs 강사 통제 | **강사가 결정** | 잘못 추천했을 때 위험이 크다. AI는 추천과 근거만 보여 준다 |
| 외부 유해성 검사 API vs AI 입력 검사 | **AI 입력 검사** | 대화 맥락까지 보고 판단할 수 있다 |
| 브라우저 vs 서버 음성 인식 | **서버** | 기기마다 인식 결과가 달라지는 문제를 없앤다 |
| 서버 vs 브라우저 표정 인식 | **브라우저** | 아이 얼굴 영상을 밖으로 보내지 않는다 |
| 시나리오 매번 생성 vs 승인본 재사용 | **재사용 우선** | 강사 검토 부담과 비용을 줄인다 |
| 풀스택 하나 vs 프론트·백엔드 분리 | **분리** | 역할극 처리가 길고 단계가 많아 백엔드 서버를 따로 두는 게 안정적이다 |

---

## 💻 기술 스택

| **분야** | **사용 기술** |
|---|---|
| **Frontend** | ![React](https://img.shields.io/badge/React-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white) ![React Router](https://img.shields.io/badge/React%20Router-CA4245?style=for-the-badge&logo=reactrouter&logoColor=white) ![TanStack Query](https://img.shields.io/badge/TanStack%20Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) ![Zustand](https://img.shields.io/badge/Zustand-443E38?style=for-the-badge&logoColor=white) ![React Hook Form](https://img.shields.io/badge/React%20Hook%20Form-EC5990?style=for-the-badge&logo=reacthookform&logoColor=white) ![Zod](https://img.shields.io/badge/Zod-3E67B1?style=for-the-badge&logo=zod&logoColor=white) ![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) ![shadcn/ui](https://img.shields.io/badge/shadcn%2Fui-000000?style=for-the-badge&logo=shadcnui&logoColor=white) |
| **Backend** | ![Java](https://img.shields.io/badge/Java%2021-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Spring Security](https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white) ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=for-the-badge&logo=spring&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white) ![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white) |
| **AI** | ![Spring AI](https://img.shields.io/badge/Spring%20AI-6DB33F?style=for-the-badge&logo=spring&logoColor=white) ![Elice](https://img.shields.io/badge/Elice%20ML%20API-6E3BFF?style=for-the-badge&logoColor=white) ![Gemini](https://img.shields.io/badge/Gemini%203.8%20Flash-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white) ![OpenAI](https://img.shields.io/badge/OpenAI%20STT·TTS-412991?style=for-the-badge&logo=openai&logoColor=white) ![Typecast](https://img.shields.io/badge/Typecast%20TTS-FF6B6B?style=for-the-badge&logoColor=white) ![TensorFlow.js](https://img.shields.io/badge/face--api-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white) |
| **Database** | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL%2018-4169E1?style=for-the-badge&logo=postgresql&logoColor=white) |
| **Infrastructure** | ![Amazon EC2](https://img.shields.io/badge/Amazon%20EC2-FF9900?style=for-the-badge&logo=amazonec2&logoColor=white) ![CloudFront](https://img.shields.io/badge/CloudFront-8C4FFF?style=for-the-badge&logo=amazonwebservices&logoColor=white) ![Amazon ECR](https://img.shields.io/badge/Amazon%20ECR-FF9900?style=for-the-badge&logo=amazonwebservices&logoColor=white) ![Amazon S3](https://img.shields.io/badge/Amazon%20S3-569A31?style=for-the-badge&logo=amazons3&logoColor=white) ![AWS Lambda](https://img.shields.io/badge/AWS%20Lambda-FF9900?style=for-the-badge&logo=awslambda&logoColor=white) ![Docker](https://img.shields.io/badge/Docker%20Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white) ![Terraform](https://img.shields.io/badge/Terraform-844FBA?style=for-the-badge&logo=terraform&logoColor=white) |
| **CI/CD · 보안** | ![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white) ![CodeQL](https://img.shields.io/badge/CodeQL-2F4F4F?style=for-the-badge&logo=github&logoColor=white) ![Gitleaks](https://img.shields.io/badge/Gitleaks-DC143C?style=for-the-badge&logoColor=white) |
| **Monitoring** | ![CloudWatch](https://img.shields.io/badge/CloudWatch-FF4F8B?style=for-the-badge&logo=amazoncloudwatch&logoColor=white) ![AWS Budgets](https://img.shields.io/badge/AWS%20Budgets-232F3E?style=for-the-badge&logo=amazonwebservices&logoColor=white) ![Agent Runs](https://img.shields.io/badge/에이전트%20실행%20대시보드-6654D9?style=for-the-badge&logoColor=white) |
| **Test** | ![JUnit5](https://img.shields.io/badge/JUnit5-25A162?style=for-the-badge&logo=junit5&logoColor=white) ![Testcontainers](https://img.shields.io/badge/Testcontainers-291A3F?style=for-the-badge&logoColor=white) ![MockServer](https://img.shields.io/badge/MockServer-4B8BBE?style=for-the-badge&logoColor=white) ![ArchUnit](https://img.shields.io/badge/ArchUnit-4B8BBE?style=for-the-badge&logoColor=white) ![Vitest](https://img.shields.io/badge/Vitest-6E9F18?style=for-the-badge&logo=vitest&logoColor=white) ![MSW](https://img.shields.io/badge/MSW-FF6A33?style=for-the-badge&logo=mockserviceworker&logoColor=white) ![Playwright](https://img.shields.io/badge/Playwright-2EAD33?style=for-the-badge&logo=playwright&logoColor=white) ![axe](https://img.shields.io/badge/axe--core-663399?style=for-the-badge&logoColor=white) |

<details>
<summary><b>기술 선정 이유</b></summary>

**프론트엔드**

| 기술 | 버전 | 선정 이유 |
|---|---|---|
| TypeScript | 6.0 | 백엔드와 주고받는 데이터 타입을 미리 맞춰 둘 수 있다 |
| React | 19.2 | 팀원 모두 사용 경험이 있다 |
| Vite | 8.3 | 백엔드를 따로 두기로 해서 Next.js 같은 서버 기능이 필요 없었다. 가볍고 빠르다 |
| React Router | 7.18 | 강사 화면과 아동 화면을 나누고, 로그인 여부에 따라 접근을 막는다 |
| TanStack Query | 5.102 | 로딩·에러·재요청 처리를 직접 짜지 않아도 된다 |
| Zustand | 5.0 | 녹음 상태처럼 여러 화면이 같이 보는 값을 간단하게 관리한다 |
| React Hook Form + Zod | 7.88 / 4.6 | 입력 항목이 많은 강사용 폼의 상태와 검증을 한 곳에서 관리한다 |
| Tailwind CSS | 4.3 | 정해 둔 색만 쓰게 해서 디자인을 통일한다 |
| shadcn/ui (Radix) | 4.21 | 강사 화면용. 키보드 이동 같은 접근성이 이미 들어 있다. 아동 화면은 캐릭터 위주라 직접 만들었다 |
| face-api | 1.7.15 | 브라우저 안에서 표정을 판단해 아이 얼굴 영상이 서버로 나가지 않는다 |
| Pretendard, 나눔스퀘어라운드 | | 강사 화면은 Pretendard, 아동 화면은 둥근 글꼴 |

**백엔드**

| 기술 | 버전 | 선정 이유 |
|---|---|---|
| Java | 21 | LTS 버전 |
| Spring Boot | 4.1.1 | 역할극 한 턴이 음성 인식 → AI 여러 번 → 음성 합성으로 길게 이어진다. 재시도와 상태 저장을 서버에서 안정적으로 처리한다 |
| Spring Security | | 강사 로그인, 아동 세션, CSRF, 권한 처리 |
| Flyway | | 스키마 변경 이력을 파일로 관리한다. 머지된 파일은 고치지 못하게 CI에서 막는다 |
| Spring AI | 2.0.1 | OpenAI 형식 API라면 주소와 모델 이름만 바꿔서 교체할 수 있다 |
| JSON Schema Validator | 3.0.1 | AI가 돌려준 JSON이 정해진 형식인지 검사한다 |
| Spotless (google-java-format) | 7.0.2 | 커밋 전에 자동으로 포맷을 맞춘다 |

</details>

---

## 🛠 시스템 아키텍처

```mermaid
flowchart LR
  B["사용자 브라우저"] -->|"HTTPS"| CF["CloudFront<br/>국내만 허용"]
  CF --> WEB
  subgraph EC2["EC2 t3.medium · Docker Compose"]
    WEB["nginx<br/>화면 파일 · /api 전달"] -->|"/api/*"| BE["backend<br/>Spring Boot"]
    BE --> PG[("postgres 18")]
  end
  BE -->|"HTTPS"| AI["외부 AI API<br/>LLM · STT · TTS"]
  SSM["SSM Parameter Store<br/>API 키"] -.-> BE
  EC2 -->|"매일 03:00 백업"| S3B[("S3")]
  EC2 -->|"로그·알람"| CW["CloudWatch"]
  GH["GitHub Actions"] -->|"OIDC 임시 권한"| ECR[("ECR")]
  ECR --> EC2
```

<div align="center">
  <img src="docs/images/readme/system-architecture.png" width="900" alt="GitHub Actions에서 AWS 배포까지 이어지는 시스템 아키텍처">
</div>

### 시스템 구성 요약

| 구성 요소 | 설명 |
|---|---|
| **배포 링크** | [https://d430pejtvizp8.cloudfront.net](https://d430pejtvizp8.cloudfront.net/) |
| **HTTPS** | 카메라·마이크는 HTTPS에서만 열리므로, 별도 도메인 없이 **CloudFront 기본 주소**로 서비스합니다. 접속은 국내에서만 허용합니다. |
| **프론트엔드** | 서버의 **nginx 컨테이너**가 화면 파일을 제공하고 `/api` 요청은 백엔드로 넘깁니다. 화면과 API가 같은 주소라 CORS 설정이 필요 없습니다. |
| **백엔드·DB** | EC2 한 대에서 Spring Boot와 PostgreSQL을 Docker Compose로 실행합니다. 실습 계정에서 RDS·로드밸런서·AWS AI 서비스를 쓸 수 없어, 컨테이너와 외부 AI API로 구성했습니다. |
| **비밀값** | API 키는 코드에 넣지 않고 **SSM Parameter Store**에 보관합니다. |
| **백업** | 매일 새벽 3시에 백업해 S3에 14일간 보관하고, **백업 파일이 실제로 복구되는지도 매일 확인**합니다. |
| **비용** | AWS Budgets 알림, 그리고 **30분 동안 안 쓰면 서버를 자동으로 끄는** EventBridge + Lambda를 둡니다. |
| **인프라 코드** | 모든 AWS 리소스를 **Terraform**으로 관리합니다. 콘솔에서 손으로 만들지 않고, PR → plan → 승인 → apply로만 바꿉니다. |

자세한 설계는 [CD·인프라 설계](docs/cd-architecture.md), [인프라(Terraform)](infra/README.md), [서버 배포](deploy/README.md)를 참고하세요.

### 인증과 보안

- **강사**: 로그인하면 토큰을 받고 12시간 동안 유지됩니다. 비밀번호와 토큰은 암호화해 저장합니다.
- **아동**: 강사가 발급한 4자리 코드로 들어옵니다. 코드를 여러 번 틀리면 일정 시간 막힙니다.
- **권한**: 강사는 자기 학급과 아동만 볼 수 있고, 로그인 없이 쓸 수 있는 API는 없습니다.
- **데이터 삭제**: 학급·아동·강사 계정을 지우면 관련 데이터도 바로 삭제됩니다.

---

## 🚀 CI/CD

<div align="center">
  <img src="docs/architecture/1-2-infra-cicd.drawio.svg" width="750" alt="CI/CD 구성도">
</div>

| 단계 | 내용 |
|---|---|
| **브랜치** | `feature/*` → `develop` → `main` (main은 멘토 리뷰) |
| **PR 검사** | 백엔드 빌드·테스트, 프론트 lint·타입 검사·테스트·빌드, E2E, 보안 검사(CodeQL, gitleaks) |
| **PR 미리보기** | PR에 `preview` 라벨을 달면 **머지 전에** 그 PR 버전을 AWS 개발 서버에 띄워 확인합니다. |
| **자동 배포** | `develop`에 머지되고 CI가 통과하면 자동으로 배포됩니다. 배포 후 서버 상태를 확인하고, 문제가 있으면 **이전 버전으로 자동으로 되돌립니다.** |
| **배포 인증** | SSH는 열지 않고, GitHub에서 AWS 권한을 임시로(OIDC) 받아 배포합니다. |
| **로컬 재현** | 모든 검사는 `bash scripts/verify.sh [frontend\|backend\|infra\|security\|e2e\|docker]`로 똑같이 돌려 볼 수 있습니다. |

전체 워크플로 목록은 [GitHub Actions 정리](docs/github-actions.md)에 있습니다.

---

## 📈 에이전트 실행 대시보드

> 🔗 [https://d430pejtvizp8.cloudfront.net/ops/agent-runs](https://d430pejtvizp8.cloudfront.net/ops/agent-runs) · 허용 목록에 등록된 계정만 볼 수 있습니다 · 자세한 설명은 [PR #146](https://github.com/kakaotechcampus-4/ktc4-chonnam-4/pull/146)

역할극 한 턴, 학습 리포트, 시나리오 생성은 AI를 여러 번 부르고, 검수 결과에 따라 다시 만들거나 안내 문구로 끝나기도 합니다. 예전에는 무엇이 왜 그렇게 흘러갔는지 보려면 서버에 들어가 로그를 `grep` 해야 했습니다. 이 대시보드는 **단계마다 어떤 판정이 나왔고, 그 때문에 다음에 무엇이 돌았는지**를 정상 흐름과 오류 흐름 모두 한 화면에서 보여 줍니다.

<div align="center">
  <img src="https://github.com/user-attachments/assets/9136b487-315c-4545-bf8d-94af59bbc9cd" width="900" alt="에이전트 실행 대시보드 첫 화면">
</div>

| 기능 | 설명 |
|---|---|
| **실행 기록** | 단계가 낸 판정(검수 통과·재생성·입력 재요청 등)을 "단계 → 판정 → 실제로 이어진 다음 단계"로 묶어 저장합니다. 글 내용·아동 이름은 남기지 않고 고정 코드만 남깁니다. |
| **흐름 분류** | 실행마다 **정상**(바로 성공) · **재시도 후 정상** · **안내로 종료**(입력 품질·안전 판정) · **실패**(호출 실패·한도·시간 초과)로 나눕니다. |
| **흐름 패턴** | 같은 단계 순서·판정으로 흘러간 실행끼리 묶어 `입력 판정 ✓ → 응답 검수 REGENERATE → 응답 생성 2 → 응답 전달 · 9건`처럼 보여 줍니다. |
| **Trace ID** | 실행마다 Trace ID를 붙이고 복사 버튼을 둬서, 서버 로그를 바로 찾을 수 있습니다. |
| **자동 진단** | 이번 기간과 바로 앞 기간을 비교해 실패 비율 급증, 응답 지연, 거절이 몰리는 단계, 새로 나타난 실패 흐름을 규칙으로 찾아 "먼저 볼 곳"을 알려 줍니다. AI를 부르지 않습니다. |
| **운영 부담** | 기본은 꺼짐이고, 저장은 별도 스레드가 맡아 아동 응답을 기다리게 하지 않습니다. 기록은 14일 뒤 지웁니다. |

<div align="center">
<table>
  <tr>
    <td align="center"><img src="https://github.com/user-attachments/assets/b1a929e5-b2c8-41d4-8820-f9d1c9cdf45e" width="420" alt="성공 흐름 구역"><br>성공 흐름</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/4d1ccdbf-9605-4a3b-a122-3ab94c2d1df2" width="420" alt="실패 흐름 구역"><br>실패 흐름</td>
  </tr>
</table>
<table>
  <tr>
    <td align="center"><img src="https://github.com/user-attachments/assets/450e1af8-7ae2-4d65-b993-8ce45fc96293" width="270" alt="실행 상세: 재시도 후 정상"><br>재시도 후 정상</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/a1fdc308-6a34-4251-838b-e03ca3ed5ffa" width="270" alt="실행 상세: 오류"><br>오류</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/7dc1be5a-5239-4abb-8ea7-5ae342e27048" width="270" alt="실행 상세: 안내로 종료"><br>안내로 종료</td>
  </tr>
</table>
<img src="https://github.com/user-attachments/assets/1593140a-42b0-45b7-bd7a-62c969fa51d1" width="900" alt="자동 진단 패널">
<br>자동 진단
</div>

> Grafana·CloudWatch Logs도 검토했지만, 서버 한 대(4GiB) 메모리와 실습 계정 권한 제한 때문에 같은 PostgreSQL에 기록하고 앱 화면으로 보는 방식을 골랐습니다.

---

## 🧪 테스트 전략

인수 조건 하나당 테스트 하나를 기준으로 작성합니다. 커버리지는 참고만 합니다.

| 구분 | 내용 |
|---|---|
| **유닛 테스트** | 난이도, 추천, 도움 단계 같은 규칙 로직 |
| **통합 테스트** | Testcontainers로 실제 PostgreSQL을 띄워 API부터 DB까지 확인합니다. AI는 MockServer 가짜 응답을 쓰며, 권한·로그 개인정보·동시 요청도 확인합니다. |
| **API 명세 테스트** | [`contracts/api-v1.json`](contracts) 하나를 백엔드와 프론트(MSW)가 함께 검사합니다. 한쪽만 바꾸면 다른 쪽 테스트가 깨집니다. |
| **E2E 테스트** | Playwright로 강사·아동 주요 흐름을 확인하고, 테스트 후 로그에 아동 이름이 남았는지 검사합니다. |
| **접근성 테스트** | axe로 화면 접근성을 검사합니다. |
| **AI 품질 평가** | 고정된 테스트 세트로 역할극(`roleplayEval`), 시나리오 생성(`scenarioEval`), 리포트 품질을 실제 모델로 측정합니다. |
| **보안** | CodeQL, gitleaks로 코드 취약점과 키 노출을 검사합니다. |

API 문서는 [`contracts/api/v1`](contracts/api/v1)에서 관리합니다. 응답은 `{ data, meta }`, 오류는 `{ error: { code, message, ... } }` 형태로 통일했고, 주요 요청은 `Idempotency-Key`를 받습니다. 테스트 작성법은 [testing.md](docs/testing.md)를 참고하세요.

---

## 📊 측정 지표

| 지표 | 측정값 |
|---|---|
| 잘못된 AI 응답이 아이에게 보인 횟수 | 목표 0건 · 검수 통과한 대사만 전달 |
| 턴당 응답 시간 | 약 17초 (11/9 측정, 개선 전 27초) |
| 턴당 AI 호출 수 · 비용 | 4회 · 약 $0.003 |
| 음성 인식 · 합성 시간 | STT 0.9초 · TTS 0.7초 |
| 시나리오 생성 성공률 | 100% (재시도 포함) · 1개당 약 $0.002 |
| 리포트 생성 성공률 | 14건 중 14건 · 1건당 약 11원 |

서비스 목표 지표로는 **같은 난이도 반복 시 목표 도달률 상승**, **난이도 승급 아동 수**, **스탬프(활동 완주) 수**를 봅니다.

---

## 🗂 디렉터리 구조

```text
ktc4-chonnam-4
├─ frontend/                 # React + Vite
│  ├─ src/features/
│  │  ├─ instructor/         # 강사: 대시보드·학급·아동·활동·시나리오 승인·리포트·추천
│  │  └─ child/              # 아동: 입장·활동 목록·표정 퀴즈·역할극 (camera/, roleplay/)
│  ├─ public/models/face-api # 브라우저 표정 인식 모델
│  └─ e2e/                   # Playwright
├─ backend/                  # Spring Boot (com.neuringo.neuringobe)
│  ├─ src/main/java/.../
│  │  ├─ auth · user         # 강사 가입·로그인, 아동 입장 코드 세션
│  │  ├─ classroom · child   # 학급·아동
│  │  ├─ goal                # 학습 목표
│  │  ├─ scenario            # 시나리오 생성·승인·재사용, 난이도 정책
│  │  ├─ activity · assignment
│  │  ├─ quiz                # 표정 퀴즈
│  │  ├─ roleplay            # 역할극 세션·턴·입력 처리
│  │  ├─ learningrecord · report  # 학습 기록·리포트·추천
│  │  ├─ ai                  # LLM·STT·TTS 연결, 프롬프트, AI 응답 검증
│  │  └─ deletion · common · config
│  ├─ src/main/resources/prompts/  # 버전별 프롬프트
│  └─ docs/                  # 역할극·리포트 설계·측정 문서
├─ contracts/                # FE↔BE API 명세 (명세 테스트 기준)
├─ infra/                    # Terraform
├─ deploy/                   # 서버 compose · 배포·백업 스크립트
├─ scripts/                  # verify.sh, 로컬 실행·검사 스크립트
└─ docs/                     # 아키텍처·CI/CD·테스트·AI 품질·PoC·와이어프레임
```

---

## ⚙️ 로컬 실행

**백엔드** (JDK 21, Docker 필요)

```bash
cd backend
cp .env.example .env   # DB_USERNAME, DB_PASSWORD 채우기
./gradlew bootRun      # PostgreSQL 컨테이너가 자동으로 뜹니다
```

**프론트엔드** (Node.js)

```bash
cd frontend
npm ci
npm run dev
```

**전체 검사**

```bash
bash scripts/verify.sh
```

AI 기능은 기본으로 꺼져 있으며, 실제 모델을 쓰려면 `backend/.env.local`에 `AI_MODEL`·`AI_BASE_URL`·`AI_API_KEY`를 넣습니다. 자세한 내용은 [백엔드 README](backend/README.md)와 [로컬 스크립트](scripts/local/README.md)를 참고하세요.

---

## 📚 문서 모음

| 주제 | 문서 | 주제 | 문서 |
|---|---|---|---|
| 시나리오 생성 프롬프트 | [scenario-generation.md](docs/scenario-generation.md) | 학습 리포트·추천·난이도 | [learning-report-loop.md](backend/docs/learning-report-loop.md) |
| AI 품질 측정 | [ai-quality-measurement.md](docs/ai-quality-measurement.md) | 역할극 응답 속도 개선 | [roleplay-latency-improvement-results](backend/docs/roleplay-latency-improvement-results-2026-11-09.md) |
| AI 제공자 공통 계약 | [ai-provider-contract.md](backend/docs/ai-provider-contract.md) | 역할극 HTTP API | [roleplay-http-api.md](backend/docs/roleplay-http-api.md) |
| CD·인프라 설계 | [cd-architecture.md](docs/cd-architecture.md) | GitHub Actions | [github-actions.md](docs/github-actions.md) |
| 테스트 가이드 | [testing.md](docs/testing.md) | API 연동 가이드 | [integration-guide.md](contracts/api/v1/integration-guide.md) |
| PoC 회고 | [poc-review](docs/poc/2026-09-09-poc-review.md) | 와이어프레임 | [week-1](docs/wireframes/week-1/README.md) |

---

<div align="center">

## 👥 팀원

<table>
  <tr>
    <th>Picture</th>
    <td align="center"><a href="https://github.com/bji0319"><img src="https://github.com/bji0319.png" width="80" height="80" alt="배재일"></a></td>
    <td align="center"><a href="https://github.com/SangHyeonY"><img src="https://github.com/SangHyeonY.png" width="80" height="80" alt="윤상현"></a></td>
    <td align="center"><a href="https://github.com/pado0711"><img src="https://github.com/pado0711.png" width="80" height="80" alt="진미나"></a></td>
    <td align="center"><a href="https://github.com/lgwoo"><img src="https://github.com/lgwoo.png" width="80" height="80" alt="이건우"></a></td>
    <td align="center"><a href="https://github.com/LearnToThink777"><img src="https://github.com/LearnToThink777.png" width="80" height="80" alt="정민서"></a></td>
  </tr>
  <tr>
    <th>Name</th>
    <td align="center">배재일</td>
    <td align="center">윤상현</td>
    <td align="center">진미나</td>
    <td align="center">이건우</td>
    <td align="center">정민서</td>
  </tr>
  <tr>
    <th>Position</th>
    <td align="center"><b>팀장</b></td>
    <td align="center"><b>FE 리드</b><br>Frontend</td>
    <td align="center"><b>BE 리드</b><br>Backend</td>
    <td align="center"><b>AI 리드</b><br>AI</td>
    <td align="center"><b>플래너&메이커</b></td>
  </tr>
  <tr>
    <th>GitHub</th>
    <td align="center"><a href="https://github.com/bji0319"><img src="https://img.shields.io/badge/bji0319-181717?style=social&logo=github"/></a></td>
    <td align="center"><a href="https://github.com/SangHyeonY"><img src="https://img.shields.io/badge/SangHyeonY-181717?style=social&logo=github"/></a></td>
    <td align="center"><a href="https://github.com/pado0711"><img src="https://img.shields.io/badge/pado0711-181717?style=social&logo=github"/></a></td>
    <td align="center"><a href="https://github.com/lgwoo"><img src="https://img.shields.io/badge/lgwoo-181717?style=social&logo=github"/></a></td>
    <td align="center"><a href="https://github.com/LearnToThink777"><img src="https://img.shields.io/badge/LearnToThink777-181717?style=social&logo=github"/></a></td>
  </tr>
</table>

</div>
