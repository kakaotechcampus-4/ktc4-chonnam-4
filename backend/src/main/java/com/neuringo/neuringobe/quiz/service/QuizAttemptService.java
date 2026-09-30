package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiDomainException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.domain.ActivityQuiz;
import com.neuringo.neuringobe.quiz.domain.InitialDifficultyPolicy;
import com.neuringo.neuringobe.quiz.domain.QuizAttempt;
import com.neuringo.neuringobe.quiz.domain.QuizHint;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizJudgement;
import com.neuringo.neuringobe.quiz.domain.QuizResult;
import com.neuringo.neuringobe.quiz.domain.QuizResultCalculator;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.dto.FinalizeQuizAttemptRequest;
import com.neuringo.neuringobe.quiz.dto.PutQuizAttemptRequest;
import com.neuringo.neuringobe.quiz.dto.QuizAttemptResponse;
import com.neuringo.neuringobe.quiz.dto.QuizHintResponse;
import com.neuringo.neuringobe.quiz.dto.QuizResultResponse;
import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizAttemptRepository;
import com.neuringo.neuringobe.quiz.repository.QuizHintRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuizAttemptService {
    private final ActivityRepository activities;
    private final ActivityQuizRepository assignments;
    private final QuizAttemptRepository attempts;
    private final QuizHintRepository hints;
    private final QuizResultRepository results;
    private final ChildRepository children;
    private final ChildAccessScope access;
    private final QuizAssignmentService assignmentService;

    public QuizAttemptService(
            ActivityRepository activities,
            ActivityQuizRepository assignments,
            QuizAttemptRepository attempts,
            QuizHintRepository hints,
            QuizResultRepository results,
            ChildRepository children,
            ChildAccessScope access,
            QuizAssignmentService assignmentService) {
        this.activities = activities;
        this.assignments = assignments;
        this.attempts = attempts;
        this.hints = hints;
        this.results = results;
        this.children = children;
        this.access = access;
        this.assignmentService = assignmentService;
    }

    @Transactional
    public SavedAttempt put(
            UUID assignmentId, PutQuizAttemptRequest request, Authentication authentication) {
        ActivityQuiz assigned = requireAssignment(assignmentId);
        Activity activity = requireLockedActivity(assigned.getActivityId(), authentication);
        QuizItem item = assignmentService.requireVersion(assigned);
        validateCameraFields(item, request);
        QuizAttempt existing = attempts.findByActivityQuizId(assignmentId).orElse(null);
        if (existing != null) {
            ensureSameFirst(existing, request);
            if (existing.isFinalized()) {
                if (sameFinal(existing, request)) {
                    return new SavedAttempt(toResponse(existing), false);
                }
                throw conflict("ATTEMPT_FINALIZED", "확정된 응답은 변경할 수 없습니다.");
            }
            return finishExisting(existing, item, activity, request);
        }

        QuizJudgement.FirstResponseResult judged;
        try {
            judged =
                    QuizJudgement.judge(
                            item.getQuizType(),
                            Set.copyOf(item.getChoices()),
                            accepted(item),
                            request.firstResponse(),
                            request.expressionMatchResult(),
                            request.technicalFailureReason() != null);
        } catch (IllegalArgumentException ex) {
            throw invalidResult();
        }
        QuizAttempt created =
                new QuizAttempt(
                        UUID.randomUUID(),
                        assignmentId,
                        request.firstResponse(),
                        judged.correct(),
                        request.expressionMatchResult(),
                        request.cameraModelVersion(),
                        reason(request),
                        judged.excludedFromScoring(),
                        Instant.now());
        attempts.saveAndFlush(created);
        activity.start(Instant.now());
        if (judged.correct() || judged.excludedFromScoring()) {
            if (request.finalResponse() != null
                    && !Objects.equals(request.finalResponse(), request.firstResponse())) {
                throw invalidResult();
            }
            created.finalizeWith(request.firstResponse(), judged.correct(), false, Instant.now());
            attempts.saveAndFlush(created);
            snapshotIfComplete(activity);
        } else if (request.finalResponse() != null) {
            if (!Objects.equals(request.finalResponse(), request.firstResponse())) {
                throw invalidResult();
            }
            created.finalizeWith(request.finalResponse(), false, false, Instant.now());
            attempts.saveAndFlush(created);
            snapshotIfComplete(activity);
        }
        return new SavedAttempt(toResponse(created), true);
    }

    public QuizAttemptResponse get(UUID assignmentId, Authentication authentication) {
        ActivityQuiz assigned = requireAssignment(assignmentId);
        requireActivity(assigned.getActivityId(), authentication);
        QuizAttempt attempt =
                attempts.findByActivityQuizId(assignmentId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ATTEMPT_NOT_FOUND", "응답을 찾을 수 없습니다."));
        return toResponse(attempt);
    }

    @Transactional
    public QuizAttemptResponse finalizeInternal(
            UUID assignmentId, FinalizeQuizAttemptRequest request, Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> "ROLE_SYSTEM".equals(a.getAuthority()))) {
            throw new ApiDomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "내부 처리 권한이 필요합니다.");
        }
        ActivityQuiz assigned = requireAssignment(assignmentId);
        Activity activity =
                activities
                        .findByIdForUpdate(assigned.getActivityId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        QuizAttempt attempt =
                attempts.findByActivityQuizId(assignmentId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ATTEMPT_NOT_FOUND", "응답을 찾을 수 없습니다."));
        if (attempt.isFinalized()) {
            throw conflict("ATTEMPT_FINALIZED", "확정된 응답은 변경할 수 없습니다.");
        }
        PutQuizAttemptRequest complete =
                new PutQuizAttemptRequest(
                        attempt.getFirstResponse(),
                        attempt.getExpressionMatchResult(),
                        attempt.getCameraModelVersion(),
                        request.finalResponse(),
                        attempt.getTechnicalFailureReason() == null
                                ? null
                                : PutQuizAttemptRequest.TechnicalFailureReason.valueOf(
                                        attempt.getTechnicalFailureReason()));
        return finishExisting(
                        attempt, assignmentService.requireVersion(assigned), activity, complete)
                .response();
    }

    @Transactional
    public QuizHintResponse issueHint(
            UUID assignmentId, UUID requestKey, Authentication authentication) {
        ActivityQuiz assigned = requireAssignment(assignmentId);
        requireLockedActivity(assigned.getActivityId(), authentication);
        QuizAttempt attempt =
                attempts.findByActivityQuizId(assignmentId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ATTEMPT_NOT_FOUND", "첫 응답을 먼저 제출해야 합니다."));
        QuizHint previous = hints.findByIdempotencyKey(requestKey).orElse(null);
        if (previous != null) {
            if (!previous.getAttemptId().equals(attempt.getAttemptId())) {
                throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 힌트 요청에 사용한 키입니다.");
            }
            return QuizHintResponse.from(previous);
        }
        if (attempt.isFinalized()
                || attempt.isExcludedFromScoring()
                || attempt.isFirstResponseCorrect()) {
            throw conflict("ATTEMPT_FINALIZED", "이 응답에는 힌트를 발급할 수 없습니다.");
        }
        int next = Math.toIntExact(hints.countByAttemptId(attempt.getAttemptId())) + 1;
        QuizItem item = assignmentService.requireVersion(assigned);
        if (next > 2 || item.getHints().size() < next) {
            throw conflict("NO_MORE_HINTS", "제공 가능한 힌트가 없습니다.");
        }
        QuizItem.HintSpec spec = item.getHints().get(next - 1);
        QuizHint hint =
                new QuizHint(
                        UUID.randomUUID(),
                        attempt.getAttemptId(),
                        requestKey,
                        next,
                        spec.type(),
                        spec.text(),
                        Instant.now());
        hints.saveAndFlush(hint);
        return QuizHintResponse.from(hint);
    }

    public QuizResultResponse result(UUID activityId, Authentication authentication) {
        requireActivity(activityId, authentication);
        QuizResult result =
                results.findById(activityId)
                        .orElseThrow(() -> conflict("QUIZ_NOT_COMPLETED", "퀴즈가 완료되지 않았습니다."));
        return QuizResultResponse.from(result);
    }

    private SavedAttempt finishExisting(
            QuizAttempt attempt, QuizItem item, Activity activity, PutQuizAttemptRequest request) {
        if (request.finalResponse() == null) {
            return new SavedAttempt(toResponse(attempt), false);
        }
        List<QuizHint> issued = hints.findByAttemptIdOrderByHintOrder(attempt.getAttemptId());
        if (issued.isEmpty() && !Objects.equals(request.firstResponse(), request.finalResponse())) {
            throw invalidResult();
        }
        boolean finalCorrect;
        try {
            finalCorrect =
                    QuizJudgement.judge(
                                    item.getQuizType(),
                                    Set.copyOf(item.getChoices()),
                                    accepted(item),
                                    request.finalResponse(),
                                    request.expressionMatchResult(),
                                    false)
                            .correct();
        } catch (IllegalArgumentException ex) {
            throw invalidResult();
        }
        attempt.finalizeWith(
                request.finalResponse(),
                finalCorrect,
                finalCorrect && !attempt.isFirstResponseCorrect() && !issued.isEmpty(),
                Instant.now());
        attempts.saveAndFlush(attempt);
        snapshotIfComplete(activity);
        return new SavedAttempt(toResponse(attempt), false);
    }

    private void ensureSameFirst(QuizAttempt existing, PutQuizAttemptRequest request) {
        if (!Objects.equals(existing.getFirstResponse(), request.firstResponse())
                || existing.getExpressionMatchResult() != request.expressionMatchResult()
                || !Objects.equals(existing.getCameraModelVersion(), request.cameraModelVersion())
                || !Objects.equals(existing.getTechnicalFailureReason(), reason(request))) {
            throw conflict("FIRST_RESPONSE_IMMUTABLE", "첫 응답은 변경할 수 없습니다.");
        }
    }

    private boolean sameFinal(QuizAttempt existing, PutQuizAttemptRequest request) {
        return Objects.equals(existing.getFinalResponse(), request.finalResponse())
                || (request.finalResponse() == null
                        && Objects.equals(
                                existing.getFinalResponse(), existing.getFirstResponse()));
    }

    private void validateCameraFields(QuizItem item, PutQuizAttemptRequest request) {
        if (item.getQuizType() == QuizType.SELF_EMOTION_SITUATION) {
            if (request.expressionMatchResult() != null
                    && (request.cameraModelVersion() == null
                            || request.cameraModelVersion().isBlank())) {
                throw invalidResult();
            }
            if (request.technicalFailureReason()
                    == PutQuizAttemptRequest.TechnicalFailureReason.IMAGE_LOAD_FAILED) {
                throw invalidResult();
            }
        } else {
            if (request.expressionMatchResult() != null || request.cameraModelVersion() != null) {
                throw invalidResult();
            }
            if (request.technicalFailureReason() != null
                    && (item.getQuizType() != QuizType.OTHER_EMOTION_IMAGE
                            || request.technicalFailureReason()
                                    != PutQuizAttemptRequest.TechnicalFailureReason
                                            .IMAGE_LOAD_FAILED)) {
                throw invalidResult();
            }
        }
    }

    private Set<String> accepted(QuizItem item) {
        List<String> values = new ArrayList<>(item.getAcceptableAnswers());
        values.add(item.getCorrectAnswer());
        return Set.copyOf(values);
    }

    private String reason(PutQuizAttemptRequest request) {
        return request.technicalFailureReason() == null
                ? null
                : request.technicalFailureReason().name();
    }

    private QuizAttemptResponse toResponse(QuizAttempt attempt) {
        return QuizAttemptResponse.from(
                attempt, hints.findByAttemptIdOrderByHintOrder(attempt.getAttemptId()));
    }

    private ActivityQuiz requireAssignment(UUID id) {
        return assignments
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "ACTIVITY_QUIZ_NOT_FOUND", "배정 문항을 찾을 수 없습니다."));
    }

    private Activity requireActivity(UUID id, Authentication authentication) {
        Activity activity =
                activities
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        access.requireOwnerOrInstructor(authentication, activity.getChildId());
        return activity;
    }

    private Activity requireLockedActivity(UUID id, Authentication authentication) {
        Activity activity =
                activities
                        .findByIdForUpdate(id)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        access.requireOwnerOrInstructor(authentication, activity.getChildId());
        return activity;
    }

    private void snapshotIfComplete(Activity activity) {
        if (results.existsById(activity.getActivityId())) {
            return;
        }
        List<ActivityQuiz> assigned =
                assignments.findByActivityIdOrderByQuestionOrder(activity.getActivityId());
        if (assigned.isEmpty()) {
            return;
        }
        List<QuizResultCalculator.FinalizedAttempt> evidence = new ArrayList<>();
        for (ActivityQuiz item : assigned) {
            QuizAttempt attempt =
                    attempts.findByActivityQuizId(item.getActivityQuizId()).orElse(null);
            if (attempt == null || !attempt.isFinalized()) {
                return;
            }
            evidence.add(
                    new QuizResultCalculator.FinalizedAttempt(
                            attempt.isFirstResponseCorrect(),
                            attempt.isExcludedFromScoring(),
                            Math.toIntExact(hints.countByAttemptId(attempt.getAttemptId())),
                            attempt.isResolvedAfterHint()));
        }
        QuizResultCalculator.Summary summary = QuizResultCalculator.calculate(evidence);
        InitialDifficultyPolicy.Decision decision = InitialDifficultyPolicy.decide(() -> summary);
        children.findByIdForUpdate(activity.getChildId())
                .orElseThrow(
                        () -> new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));
        boolean first = !results.existsInitialDifficultyUsedByChild(activity.getChildId());
        results.saveAndFlush(
                new QuizResult(activity.getActivityId(), summary, decision, first, Instant.now()));
        if (first) {
            activity.applyInitialDifficulty(
                    decision.initialSupportLevel(), decision.fallbackApplied());
        }
    }

    private ApiDomainException invalidResult() {
        return new ApiDomainException(
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_QUIZ_RESULT", "퀴즈 응답 값이 올바르지 않습니다.");
    }

    private ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT, code, message);
    }

    public record SavedAttempt(QuizAttemptResponse response, boolean created) {}
}
