package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.Objects;

/** 다시 쓸 때 넘기는 이전 초안과 고칠 점. 규칙 검사에서 걸렸든 평가 모델이 돌려보냈든 같은 모양이다. */
public record ReportRevision(
        ReportDraft previousDraft, List<String> failureCodes, RevisionInstruction instruction) {

    public ReportRevision {
        Objects.requireNonNull(previousDraft, "previousDraft must not be null");
        Objects.requireNonNull(failureCodes, "failureCodes must not be null");
        Objects.requireNonNull(instruction, "instruction must not be null");
        failureCodes = List.copyOf(failureCodes);
        if (failureCodes.isEmpty()) {
            throw new IllegalArgumentException("a revision needs at least one failure code");
        }
    }
}
