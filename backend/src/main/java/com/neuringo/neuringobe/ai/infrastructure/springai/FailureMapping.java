package com.neuringo.neuringobe.ai.infrastructure.springai;

import com.neuringo.neuringobe.ai.application.model.AiFailure;
import java.util.Objects;

public record FailureMapping(AiFailure failure, FailureMappingSource source) {

    public FailureMapping {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }

    public boolean usedFallback() {
        return source == FailureMappingSource.NAME_FALLBACK;
    }
}
