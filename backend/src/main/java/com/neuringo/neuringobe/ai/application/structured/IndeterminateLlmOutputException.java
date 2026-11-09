package com.neuringo.neuringobe.ai.application.structured;

/** 구조는 유효하지만 모델이 판단을 보류한 결과다. 원문이나 자유 서술 사유를 담지 않는다. */
public final class IndeterminateLlmOutputException extends RuntimeException {
    public IndeterminateLlmOutputException() {
        super("evaluation review has insufficient context");
    }
}
