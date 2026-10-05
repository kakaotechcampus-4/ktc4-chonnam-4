package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.RevisionInstruction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 응답 재생성 지시(워크플로우 v3 13.1). 응답 판단이 REGENERATE·SAFETY_REGENERATE 일 때만 만든다.
 *
 * <p>이때 실패 코드는 출력 검사가, 수정 지시는 EvaluationResult 가 이미 필수로 보장하므로 세 값 모두 필수다. 빈 값으로 받아 주면 호출하는 쪽이 빠뜨린
 * 것을 숨기게 된다. 형식 오류로 후보를 읽지 못했으면 실패 후보가 없으므로 이 객체 없이 같은 요청을 다시 보낸다.
 */
public record GenerationRetry(
        List<FailedCandidate> failedCandidates,
        List<String> failureCodes,
        RevisionInstruction revisionInstruction) {

    public GenerationRetry {
        failedCandidates =
                List.copyOf(Objects.requireNonNull(failedCandidates, "failedCandidates"));
        failureCodes = List.copyOf(Objects.requireNonNull(failureCodes, "failureCodes"));
        Objects.requireNonNull(revisionInstruction, "revisionInstruction");
        if (failedCandidates.isEmpty()) {
            throw new IllegalArgumentException("failedCandidates must not be empty");
        }
        if (failureCodes.isEmpty()) {
            throw new IllegalArgumentException("failureCodes must not be empty");
        }
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
