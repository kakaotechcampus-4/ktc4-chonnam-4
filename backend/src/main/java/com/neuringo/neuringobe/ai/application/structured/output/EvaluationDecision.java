package com.neuringo.neuringobe.ai.application.structured.output;

public enum EvaluationDecision {
    PASS,
    REGENERATE,
    REANALYZE,
    CONFIRM_INPUT,
    RETRY_EVALUATION,
    SAFETY_REGENERATE,
    SAFE_FALLBACK,
    SAFETY_ESCALATION
}
