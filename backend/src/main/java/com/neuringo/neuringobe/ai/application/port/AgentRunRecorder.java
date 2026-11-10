package com.neuringo.neuringobe.ai.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * 에이전트 워크플로우 한 번의 실행을 단계·판정 단위로 남긴다. 기본 구현은 아무것도 남기지 않는다.
 *
 * <p>넘기는 값은 고정 코드·횟수·시간·토큰 수뿐이다. 발화·프롬프트·모델 출력 글, 아동·세션 식별자는 넘기지 않는다. 기록이 실패해도 워크플로우 결과는 바뀌지 않아야
 * 하므로 구현은 예외를 밖으로 던지지 않는다.
 */
public interface AgentRunRecorder {
    AgentRunRecorder NONE = new AgentRunRecorder() {};

    enum Workflow {
        ROLEPLAY_TURN,
        LEARNING_REPORT,
        SCENARIO_GENERATION
    }

    /** 실행을 연다. 열지 않은 실행의 사건은 버린다. */
    default void begin(UUID runId, Workflow workflow) {}

    /** RoleplayTiming 이 내보내는 구간(STAGE)·요약(SUMMARY)·판정(VALIDATION, DECISION) 사건. */
    default void event(UUID runId, Map<String, Object> event) {}

    /** 실행을 닫고 저장한다. result 는 최종 결과 코드, failed 는 오류로 끝났는지다. */
    default void end(UUID runId, Map<String, Object> result) {}
}
