package com.neuringo.neuringobe.ai.application.structured;

import java.util.List;

/**
 * 판정 단계의 출력이 다음 단계를 어떻게 정했는지 고정 코드로 드러낸다. 실행 기록(AgentRunRecorder)이 "어떤 판정 때문에 무엇이 다시 돌았는가"를 글 내용 없이
 * 남길 때 쓴다.
 */
public interface JudgedOutput {
    /** 판정 코드. 예: PASS, REGENERATE, ANALYZABLE. */
    String verdict();

    /** 이 출력으로 다음 단계에 그대로 넘어갈 수 있는가. */
    boolean accepted();

    /** 판정이 지목한 다음 단계. 없으면 null. */
    default String nextStep() {
        return null;
    }

    /** 거절 사유 코드. 없으면 빈 목록. */
    default List<String> verdictCodes() {
        return List.of();
    }
}
