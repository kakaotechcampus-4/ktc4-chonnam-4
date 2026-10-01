package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 응답 재생성 지시(워크플로우 v3 13.1). 실패한 후보와 응답 판단의 수정 지시를 함께 넘긴다. */
public record GenerationRetry(
        List<FailedCandidate> failedCandidates,
        List<String> failureCodes,
        RevisionInstruction revisionInstruction) {

    public GenerationRetry {
        failedCandidates = List.copyOf(Objects.requireNonNull(failedCandidates));
        if (failedCandidates.isEmpty()) {
            throw new IllegalArgumentException("failedCandidates must not be empty");
        }
        failureCodes = failureCodes == null ? List.of() : List.copyOf(failureCodes);
        revisionInstruction =
                revisionInstruction == null
                        ? new RevisionInstruction(null, null, null, null)
                        : revisionInstruction;
    }

    public List<UUID> failedCandidateIds() {
        return failedCandidates.stream().map(FailedCandidate::candidateId).toList();
    }

    public record FailedCandidate(UUID candidateId, String text) {

        public FailedCandidate {
            Objects.requireNonNull(candidateId, "candidateId must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }
}
