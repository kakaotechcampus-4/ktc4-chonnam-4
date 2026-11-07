package com.neuringo.neuringobe.ai.application.report;

import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import java.util.Objects;

/**
 * 규칙 검사에서 찾은 문제 하나.
 *
 * @param detail 다시 쓸 때 피할 것으로 넘기는 짧은 설명(예: 없는 근거 ID). 초안 문장을 그대로 담지 않는다.
 */
public record ReportIssue(String code, String detail) {

    public ReportIssue {
        Objects.requireNonNull(code, "code must not be null");
        if (!ReportCodes.ALL.contains(code)) {
            throw new IllegalArgumentException("unknown report code: " + code);
        }
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
