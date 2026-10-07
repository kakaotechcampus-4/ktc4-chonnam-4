package com.neuringo.neuringobe.ai.application.prompt;

import java.util.Set;

/**
 * 역할극 프롬프트 v1 이 모델에게 허용하는 코드값. 프롬프트 파일(prompts/roleplay/v1)의 표와 같아야 하며 RoleplayPromptsTest 가 대조한다.
 * 값을 바꾸면 프롬프트 버전을 올린다.
 */
public final class RoleplayCodes {

    private RoleplayCodes() {}

    public static final Set<String> SCENARIO_LEVELS = Set.of("L1", "L2", "L3");

    public static final Set<String> SUPPORT_LEVELS = Set.of("S0", "S1", "S2", "S3");

    public static final Set<String> MICRO_GOAL_STATUSES =
            Set.of("NOT_OBSERVED", "IN_PROGRESS", "ACHIEVED", "ANSWER_REVEALED");

    public static final Set<String> RESPONSE_ACTS =
            Set.of(
                    "ANSWER_ATTEMPT",
                    "DONT_KNOW",
                    "HELP_REQUEST",
                    "QUESTION_BACK",
                    "AGREEMENT_ONLY",
                    "OFF_TOPIC_REMARK",
                    "NEGATIVE_EXPRESSION",
                    "REFUSAL");

    public static final Set<String> RELEVANCE =
            Set.of("RELEVANT", "PARTIALLY_RELEVANT", "IRRELEVANT");

    public static final Set<String> LEARNING_STATES =
            Set.of(
                    "GOAL_ACHIEVED",
                    "CORRECT_BUT_SHALLOW",
                    "PARTIAL_UNDERSTANDING",
                    "MISCONCEPTION",
                    "OFF_TOPIC",
                    "HELP_NEEDED",
                    "NOT_ASSESSABLE");

    public static final Set<String> GAP_CODES =
            Set.of(
                    "NONE",
                    "MISSING_EVENT",
                    "MISSING_EMOTION",
                    "MISSING_CAUSE_LINK",
                    "MISSING_ACTION",
                    "EMOTION_MISMATCH",
                    "CAUSE_MISCONCEPTION",
                    "NO_CONTENT",
                    "OFF_TOPIC",
                    "UNCLEAR_INPUT");

    public static final Set<String> STRATEGY_TYPES =
            Set.of(
                    "PROBE_EVENT",
                    "PROBE_EMOTION",
                    "PROBE_CAUSE",
                    "PROBE_ACTION",
                    "REDIRECT_TO_SCENARIO",
                    "CONFIRM_UNDERSTANDING",
                    "ADVANCE_GOAL",
                    "CLOSE_ROLEPLAY");

    public static final Set<String> RESPONSE_TYPES =
            Set.of("GUIDING_QUESTION", "ANSWER_REVEAL", "REDIRECT", "CONFIRMATION", "CLOSING");

    /** 물음표가 반드시 하나 있어야 하는 응답 유형. */
    public static final Set<String> QUESTION_RESPONSE_TYPES =
            Set.of("GUIDING_QUESTION", "REDIRECT", "CONFIRMATION");

    /** 치명 실패. 하나라도 있으면 전달하지 않는다. */
    public static final Set<String> CRITICAL_FAILURE_CODES =
            Set.of(
                    "UNSAFE_EXPRESSION",
                    "ASKS_PERSONAL_INFO",
                    "SCENARIO_CONTRADICTION",
                    "REINFORCES_INCORRECT_ANSWER",
                    "INTERNAL_INSTRUCTION_LEAK",
                    "IMMEDIATE_RISK_IN_INPUT");

    public static final Set<String> FAILURE_CODES =
            Set.of(
                    "UNSAFE_EXPRESSION",
                    "ASKS_PERSONAL_INFO",
                    "SCENARIO_CONTRADICTION",
                    "REINFORCES_INCORRECT_ANSWER",
                    "INTERNAL_INSTRUCTION_LEAK",
                    "IMMEDIATE_RISK_IN_INPUT",
                    "EMPTY_OR_TRUNCATED",
                    "MISSING_QUESTION",
                    "MULTIPLE_QUESTIONS",
                    "TOO_LONG",
                    "TOO_DIFFICULT",
                    "ANSWER_TOO_DIRECT",
                    "EMOTION_ASSERTED_AS_FACT",
                    "PROHIBITED_INFERENCE",
                    "UNSUPPORTED_CAUSE_ANALYSIS",
                    "STATE_STRATEGY_MISMATCH",
                    "STT_TREATED_AS_LEARNING_GAP",
                    "ANALYSIS_RESPONSE_MISMATCH",
                    "GOAL_SKIPPED",
                    "SUPPORT_LEVEL_MISMATCH",
                    "REPEATED_QUESTION",
                    "NOT_ANSWERABLE",
                    "JUDGMENTAL_TONE",
                    "INPUT_MEANING_UNCLEAR");

    static void requireOneOf(Set<String> allowed, String value, String fieldName) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(fieldName + " has an unsupported value");
        }
    }
}
