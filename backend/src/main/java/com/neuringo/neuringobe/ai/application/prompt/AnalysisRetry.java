package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.List;

/**
 * 원인 재분석 지시(워크플로우 v3 13.2). 응답 판단의 REANALYZE 결과나 형식 실패에서 만든다.
 *
 * @param failureCodes 이전 분석이 실패한 이유
 * @param focus 이번 분석에서 집중할 것
 * @param doNotAssume 가정하지 말아야 할 것
 * @param failedAnalysis 검수에서 거절된 직전 분석. 형식 오류로 분석이 없으면 null
 */
public record AnalysisRetry(
        List<String> failureCodes,
        List<String> focus,
        List<String> doNotAssume,
        AnalysisResult failedAnalysis) {

    public AnalysisRetry(List<String> failureCodes, List<String> focus, List<String> doNotAssume) {
        this(failureCodes, focus, doNotAssume, null);
    }

    public AnalysisRetry {
        failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
        focus = focus == null ? List.of() : List.copyOf(focus);
        doNotAssume = doNotAssume == null ? List.of() : List.copyOf(doNotAssume);
    }
}
