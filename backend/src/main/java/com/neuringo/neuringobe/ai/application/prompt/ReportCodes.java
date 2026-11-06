package com.neuringo.neuringobe.ai.application.prompt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 리포트 초안의 실패 코드. 규칙 검사(ReportQualityRule)와 평가 모델이 같은 코드를 쓴다. 고쳐 쓰기 지시는 코드마다 정해 둔 문장으로 만들어서, 이전 초안
 * 내용을 그대로 지시에 옮기지 않는다.
 */
public final class ReportCodes {

    public static final String UNKNOWN_EVIDENCE = "UNKNOWN_EVIDENCE";
    public static final String MISSING_EVIDENCE = "MISSING_EVIDENCE";
    public static final String UNGROUNDED_CLAIM = "UNGROUNDED_CLAIM";
    public static final String DIAGNOSTIC_LANGUAGE = "DIAGNOSTIC_LANGUAGE";
    public static final String TECHNICAL_ERROR_AS_DIFFICULTY = "TECHNICAL_ERROR_AS_DIFFICULTY";
    public static final String MISPLACED_ITEM = "MISPLACED_ITEM";
    public static final String NOT_ACTIONABLE = "NOT_ACTIONABLE";
    public static final String HARD_TO_READ = "HARD_TO_READ";
    public static final String TOO_LONG = "TOO_LONG";

    private static final Map<String, String> GUIDANCE = new LinkedHashMap<>();

    static {
        GUIDANCE.put(UNKNOWN_EVIDENCE, "evidence 에 없는 ID 를 쓰지 말고 입력의 ID 만 쓴다");
        GUIDANCE.put(MISSING_EVIDENCE, "모든 문장에 근거 ID 를 하나 이상 단다");
        GUIDANCE.put(UNGROUNDED_CLAIM, "근거에 없는 행동·감정·원인을 더하지 않고 관찰한 사실만 쓴다");
        GUIDANCE.put(DIAGNOSTIC_LANGUAGE, "진단명·성향·능력을 단정하지 않고 관찰한 행동으로 쓴다");
        GUIDANCE.put(TECHNICAL_ERROR_AS_DIFFICULTY, "기술 오류(음성 인식·연결 문제)를 아동의 어려움에 쓰지 않는다");
        GUIDANCE.put(MISPLACED_ITEM, "지원 없이 도달한 목표는 어려운 지점에, 열린 질문은 효과적인 지원에 쓰지 않는다");
        GUIDANCE.put(NOT_ACTIONABLE, "다음 활동에서 강사가 쓸 수 있는 지원과 어려운 지점을 근거와 함께 쓴다");
        GUIDANCE.put(HARD_TO_READ, "한 문장에 한 가지만 짧고 쉬운 말로 쓴다");
        GUIDANCE.put(TOO_LONG, "글자 수와 항목 수 제한을 지킨다");
    }

    public static final Set<String> ALL = Set.copyOf(GUIDANCE.keySet());

    private ReportCodes() {}

    public static String guidance(String code) {
        String guidance = GUIDANCE.get(code);
        if (guidance == null) throw new IllegalArgumentException("unknown report code: " + code);
        return guidance;
    }
}
