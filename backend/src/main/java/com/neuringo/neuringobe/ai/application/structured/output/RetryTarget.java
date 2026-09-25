package com.neuringo.neuringobe.ai.application.structured.output;

public enum RetryTarget {
    RESPONSE_GENERATION,
    CAUSE_ANALYSIS,
    INPUT_CONFIRMATION,
    RESPONSE_EVALUATION,
    SAFE_FALLBACK,
    SAFETY_ESCALATION
}
