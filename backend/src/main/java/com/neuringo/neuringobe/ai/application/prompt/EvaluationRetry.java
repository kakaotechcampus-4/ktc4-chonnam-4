package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;

/** 모델 원문이나 예외 내용을 다시 보내지 않고 서버가 확인한 수정 조건만 전달한다. */
public enum EvaluationRetry {
    DIRECTNESS_CONTRACT_CONFLICT(
            "ANSWER_TOO_DIRECT에는 answer_directness_violation의 실제 발화 인용과 허용 사유가 필요하다. S3의 해당 목표 직접 표현을 TARGET_ANSWER_BEFORE_S3로 거절하지 않는다. 마스코트 거절은 실제 힌트에서 인용한다. 코드·사유·difficulty_appropriate를 일치시키고 다른 목표·행동 주체·안전 문제는 해당 검사로 독립 판단한다. 통과하려고 코드를 지우거나 PASS로 바꾸지 않는다."),
    REVIEW_SCOPE_INVALID(
            "이번 observation_tasks의 키만 빠짐없이 검수한다. 현재 목표 ID·상태와 활동 판정은 요청값을 검수하고 다른 목표나 후보 대사를 근거로 삼지 않는다."),
    ACTIVITY_REVIEW_INVALID(
            "검수 대상의 reviewed_decision은 분석의 활동 판정과 같아야 한다. 활동 달성과 판정의 타당성을 구분하고 실제 관찰 참조·허용 사유를 쓴다. 통과하려고 승인하지 않는다."),
    ANALYSIS_LOGIC_REVIEW_INVALID("관찰·활동 검수와 구분해 발화 해석·학습 상태·격차·전략·지원의 논리를 검수하고 판정과 허용 사유를 일치시킨다."),
    OBSERVATION_REVIEW_INVALID(
            "이번 관찰의 목표·기준 조합을 빠짐없이 한 번씩 검수한다. 인용은 이번 발화의 정확한 연속 구절을 쓰고 없는 표현은 만들지 않는다. 관찰 분류·판정·사유를 일치시킨다."),
    OBSERVATION_CONTEXT_INSUFFICIENT(
            "관찰·현재 목표·활동·해석/전략를 독립 검수하고 부족한 정보를 사유로 명시한다. 정보 부족은 아동의 오답이 아니며 통과하려고 SUPPORTED나 PASS로 바꾸지 않는다."),
    INVALID_STRUCTURE("유효한 JSON 객체 하나와 필수 필드를 출력한다. failure_codes는 허용 코드 문자열의 배열이며 속성을 넣지 않는다."),
    UNAPPROVED_GOAL_REVIEW(
            "관찰·현재 목표·활동·해석/전략가 모두 승인될 때만 PASS할 수 있다. 분석 근거가 틀리면 기존 안전·입력 확인 우선순위를 지키며 REANALYZE한다. 검사를 통과하려고 검수 값을 true로 바꾸지 않는다."),
    UNKNOWN_FAILURE_CODE(
            "failure_codes는 retry.allowed_failure_codes에서만 선택한다. 분석 실패는 전용 검수에 기록하며 서버 전용 분석 코드를 failure_codes에 쓰지 않는다. checks 항목명·JSON 필드 경로·새 코드를 넣지 않는다."),
    INCONSISTENT_DECISION(
            "PASS는 checks와 목표 검수가 모두 true이고 failure_codes=[]이어야 한다. 다른 판단은 실패 코드를 하나 이상 쓴다. 판정을 임의로 PASS로 바꾸지 않는다."),
    REVIEW_ANALYSIS_CONFLICT(
            "전체 분석 승인은 서버가 계산한다. 일반 경로에서 분석 검수에 거절이 있으면 REANALYZE, 모두 승인되면 후보 검수 결과에 맞는 판정을 선택한다. 근거를 다시 검토해 판단과 검수 값을 일치시킨다. 안전·입력 확인 우선순위를 지키고 통과하려고 값을 true로 바꾸지 않는다."),
    FAILURE_CHECK_CONFLICT(
            "GOAL_SKIPPED이면 checks.goal_aligned=false, ANALYSIS_RESPONSE_MISMATCH이면 checks.analysis_response_consistent=false여야 한다. 실제 근거에 따라 코드와 검사를 다시 판단하며 통과하려고 코드를 지우지 않는다."),
    REGENERATION_ANALYSIS_CONFLICT(
            "REGENERATE·SAFETY_REGENERATE는 관찰·현재 목표·활동·해석/전략 검수가 모두 승인될 때만 선택한다. 분석 근거가 틀리면 안전·입력 확인 우선순위를 지키며 REANALYZE한다. 검사를 통과하려고 값을 true로 바꾸지 않는다.");

    private final String correction;

    EvaluationRetry(String correction) {
        this.correction = correction;
    }

    public String correction() {
        return correction;
    }

    public static EvaluationRetry from(InvalidLlmOutputException failure) {
        EvaluationRetry direct = knownReason(failure.getMessage());
        if (direct != null) return direct;
        Throwable cause = failure.getCause();
        while (cause != null) {
            EvaluationRetry nested = knownReason(cause.getMessage());
            if (nested != null) return nested;
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return INVALID_STRUCTURE;
    }

    private static EvaluationRetry knownReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case AnswerDirectnessViolation.CONFLICT -> DIRECTNESS_CONTRACT_CONFLICT;
            case "목표 관찰과 활동 목표 판정이 모두 승인되어야 합니다.", "근거 없는 분석은 후보만 재생성할 수 없습니다." ->
                    UNAPPROVED_GOAL_REVIEW;
            case "observation review references do not match",
                            "current observation reviews are missing",
                            "observation explanation is invalid",
                            "observation quote is not in the learner utterance",
                            "observation verdict and evidence conflict",
                            "unsupported observation needs analysis failure code" ->
                    OBSERVATION_REVIEW_INVALID;
            case "activity review is required",
                            "activity review decision does not match",
                            "activity review explanation is invalid",
                            "activity review verdict and reason conflict",
                            "activity review references do not match",
                            "activity review lacks positive assessment basis" ->
                    ACTIVITY_REVIEW_INVALID;
            case "analysis logic review is required",
                            "analysis logic review explanation is invalid",
                            "analysis logic review verdict and reason conflict" ->
                    ANALYSIS_LOGIC_REVIEW_INVALID;
            case "observation review keys do not match",
                            "current goal review targets do not match" ->
                    REVIEW_SCOPE_INVALID;
            case "unknown failure code" -> UNKNOWN_FAILURE_CODE;
            case "goal review contradicts analysis check", "analysis review contradicts decision" ->
                    REVIEW_ANALYSIS_CONFLICT;
            case "failure code contradicts evaluation check" -> FAILURE_CHECK_CONFLICT;
            case "regeneration requires supported analysis" -> REGENERATION_ANALYSIS_CONFLICT;
            case "PASS must be safe with no failure codes",
                            "PASS requires all checks to pass",
                            "only PASS can be safe to send",
                            "a failed evaluation needs failure codes" ->
                    INCONSISTENT_DECISION;
            default -> null;
        };
    }
}
