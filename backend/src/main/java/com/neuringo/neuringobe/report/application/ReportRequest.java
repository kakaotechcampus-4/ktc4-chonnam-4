package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.domain.ReportEvidence;
import java.util.Objects;
import java.util.UUID;

/** 완료 활동 하나의 리포트 생성 요청. ID 들은 추적 문맥에만 쓰고 프롬프트에는 넣지 않는다. */
public record ReportRequest(
        UUID requestId, UUID activityId, UUID classId, UUID childId, ReportEvidence evidence) {

    public ReportRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(activityId, "activityId must not be null");
        Objects.requireNonNull(childId, "childId must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
