package com.neuringo.neuringobe.ai.infrastructure.springai;

public enum FailureMappingSource {
    OPENAI_EXCEPTION_TYPE,
    OPENAI_STATUS_CODE,
    SPRING_EXCEPTION_TYPE,
    JDK_EXCEPTION_TYPE,
    NAME_FALLBACK
}
