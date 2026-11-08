package com.neuringo.neuringobe.ai.application.prompt;

/** 리포트 초안 길이 제한. 프롬프트에 그대로 넣고, 규칙 검사도 같은 값으로 막는다. */
public record ReportLimits(int summaryMaxChars, int itemMaxChars, int itemsPerSectionMax) {

    public static final ReportLimits DEFAULT = new ReportLimits(120, 80, 3);

    public ReportLimits {
        if (summaryMaxChars < 1 || itemMaxChars < 1 || itemsPerSectionMax < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
