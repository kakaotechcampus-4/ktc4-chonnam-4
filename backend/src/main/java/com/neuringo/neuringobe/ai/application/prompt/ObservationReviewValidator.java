package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.IndeterminateLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ObservationReviewValidator {
    private final Map<Key, GoalObservationOutput.Status> expected = new HashMap<>();
    private final String utterance;

    ObservationReviewValidator(
            RoleplayTurnInput input,
            GoalObservationOutput.Analysis analysis,
            GoalObservationInput observations) {
        utterance = input.learnerTurn().canonicalUtterance();
        for (var observation : analysis.observations()) {
            var ref = observation.reference();
            if (!ref.turnId().equals(input.learnerTurn().turnId())
                    || !observations.contains(ref.microGoalId(), ref.criterionReference())
                    || expected.put(
                                    new Key(ref.microGoalId(), ref.criterionReference()),
                                    observation.status())
                            != null) {
                throw new IllegalArgumentException("검수할 관찰이 이번 입력·승인 기준과 일치하지 않습니다.");
            }
        }
    }

    boolean validate(List<ObservationReview> reviews) {
        var seen = new HashSet<Key>();
        boolean unsupported = false, indeterminate = false;
        for (var review : reviews) {
            var key = new Key(review.microGoalId(), review.criterionReference());
            require(
                    expected.containsKey(key) && seen.add(key),
                    "observation review references do not match");
            String explanation = review.explanation();
            require(
                    !explanation.isBlank()
                            && explanation.codePointCount(0, explanation.length()) <= 200,
                    "observation explanation is invalid");
            String quote = review.evidenceQuote();
            require(
                    quote == null || (!quote.isBlank() && utterance.contains(quote)),
                    "observation quote is not in the learner utterance");
            var status = expected.get(key);
            String reason = review.reasonCode();
            boolean valid =
                    switch (review.verdict()) {
                        case SUPPORTED ->
                                status == GoalObservationOutput.Status.NOT_EXPRESSED
                                        ? reason.equals("ABSENCE_CONFIRMED")
                                        : reason.equals("MATCHED_EXPRESSION") && quote != null;
                        case UNSUPPORTED ->
                                reason.equals("STATUS_MISMATCH") && quote != null
                                        || reason.equals("NO_MATCHING_EXPRESSION")
                                                && status
                                                        != GoalObservationOutput.Status
                                                                .NOT_EXPRESSED;
                        case INSUFFICIENT_CONTEXT -> reason.equals("INSUFFICIENT_CONTEXT");
                    };
            require(valid, "observation verdict and evidence conflict");
            unsupported |= review.verdict() == ObservationReview.Verdict.UNSUPPORTED;
            indeterminate |= review.verdict() == ObservationReview.Verdict.INSUFFICIENT_CONTEXT;
        }
        require(seen.equals(expected.keySet()), "current observation reviews are missing");
        if (unsupported) return false;
        if (indeterminate) throw new IndeterminateLlmOutputException();
        return true;
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new InvalidLlmOutputException(reason, null);
    }

    private record Key(UUID goal, String criterion) {}
}
