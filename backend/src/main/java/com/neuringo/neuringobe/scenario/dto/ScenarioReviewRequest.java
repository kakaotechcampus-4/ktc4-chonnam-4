package com.neuringo.neuringobe.scenario.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.domain.ScenarioReviewAction;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public record ScenarioReviewRequest(
        @NotNull ScenarioReviewAction action, UUID reviewId, Long revision) {
    public void validate() {
        boolean valid =
                action == ScenarioReviewAction.START_OR_RESUME
                        ? reviewId == null && revision == null
                        : action == ScenarioReviewAction.NEXT
                                && reviewId != null
                                && revision != null
                                && revision > 0;
        if (!valid)
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_SCENARIO_REVIEW_REQUEST",
                    "검토 동작과 검토 번호를 확인해 주세요.");
    }

    @JsonAnySetter
    public void unknown(String name, Object value) {
        throw new IllegalArgumentException("허용하지 않은 요청 항목입니다.");
    }
}
