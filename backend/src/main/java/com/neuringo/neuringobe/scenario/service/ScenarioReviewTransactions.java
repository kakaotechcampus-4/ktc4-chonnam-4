package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioConfirmationPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioEdits;
import com.neuringo.neuringobe.scenario.dto.ScenarioGenerationContext;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewClosure;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewSnapshot;
import com.neuringo.neuringobe.scenario.dto.ScenarioReviewStartAttempt;
import com.neuringo.neuringobe.scenario.dto.ScenarioVersionRef;
import com.neuringo.neuringobe.scenario.repository.ScenarioConfirmationRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioDraftRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioReviewRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ScenarioReviewTransactions {
    private final InitialScenarioPreparationService preparations;
    private final ScenarioReviewRepository reviews;
    private final ChildAccessScope access;
    private final ScenarioDraftRepository drafts;
    private final ScenarioConfirmationRepository confirmations;
    private final ScenarioApprovalRegistry approvals;
    private final JsonMapper json;

    public ScenarioReviewTransactions(
            InitialScenarioPreparationService preparations,
            ScenarioReviewRepository reviews,
            ChildAccessScope access,
            ScenarioDraftRepository drafts,
            ScenarioConfirmationRepository confirmations,
            ScenarioApprovalRegistry approvals,
            JsonMapper json) {
        this.preparations = preparations;
        this.reviews = reviews;
        this.access = access;
        this.drafts = drafts;
        this.confirmations = confirmations;
        this.approvals = approvals;
        this.json = json;
    }

    @Transactional
    public ScenarioReviewStartAttempt resume(
            InitialScenarioPreparation expected, UUID requestId, Authentication authentication) {
        var existing = start(expected, requestId, authentication, null, null);
        return new ScenarioReviewStartAttempt(
                existing.orElse(null), reviews.epoch(expected.initialDifficulty().activityId()));
    }

    @Transactional
    public ScenarioReviewSnapshot createOrResume(
            InitialScenarioPreparation expected,
            UUID requestId,
            Authentication authentication,
            List<ApprovedScenarioVersion> candidates,
            long expectedEpoch) {
        return start(expected, requestId, authentication, List.copyOf(candidates), expectedEpoch)
                .orElseThrow();
    }

    private Optional<ScenarioReviewSnapshot> start(
            InitialScenarioPreparation expected,
            UUID requestId,
            Authentication authentication,
            List<ApprovedScenarioVersion> candidates,
            Long expectedEpoch) {
        UUID activity = expected.initialDifficulty().activityId();
        var currentInput = lockAndPrepare(activity, authentication);
        UUID instructor = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        var receipt = reviews.request(activity, requestId);
        if (receipt.isPresent()) {
            var recorded = receipt.get();
            if (!"START_OR_RESUME".equals(recorded.get("request_kind")))
                throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 동작에 사용한 요청 식별자입니다.");
            if (!"ACTIVE".equals(recorded.get("outcome")))
                throw conflict("SCENARIO_REVIEW_CLOSED", "이미 종료된 검토 요청입니다.");
        }
        if (expectedEpoch != null && expectedEpoch != reviews.epoch(activity))
            throw conflict("SCENARIO_REVIEW_CHANGED", "조회 중 검토가 취소되었습니다. 다시 시작해 주세요.");
        if (!fingerprint(expected).equals(fingerprint(currentInput)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "시나리오 준비 입력이 변경되었습니다.");
        if (reviews.isAssigned(activity))
            throw conflict("SCENARIO_ALREADY_ASSIGNED", "이미 시나리오가 배정된 활동입니다.");
        var existing = reviews.find(activity);
        boolean created = false;
        if (existing.isEmpty()) {
            if (receipt.isPresent()) throw conflict("SCENARIO_REVIEW_CLOSED", "이전 검토를 복원할 수 없습니다.");
            if (candidates == null) return Optional.empty();
            reviews.create(
                    UUID.randomUUID(), activity, instructor, fingerprint(currentInput), candidates);
            existing = reviews.find(activity);
            created = true;
        }
        var review = existing.orElseThrow();
        if (!review.instructorId().equals(instructor)
                || !review.inputFingerprint().equals(fingerprint(currentInput)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "기존 검토의 입력과 일치하지 않습니다.");
        if (receipt.isPresent() && !review.reviewId().equals(receipt.get().get("review_id")))
            throw conflict("SCENARIO_REVIEW_CLOSED", "이전 검토의 요청 식별자입니다.");
        var available = reviews.firstAvailable(review, currentInput.initialDifficulty().childId());
        Integer position = available.map(c -> c.position()).orElse(null);
        boolean moved = !Objects.equals(position, review.currentPosition());
        if (moved && !created && receipt.isPresent()) {
            throw conflict("SCENARIO_REVIEW_CHANGED", "후보 상태가 변경되었습니다. 검토를 다시 불러와 주세요.");
        }
        if (moved) {
            reviews.setPosition(review.reviewId(), position, !created);
            available.ifPresent(
                    c -> reviews.recordPresentation(instructor, c.scenario().scenarioId()));
        }
        if (receipt.isEmpty()) reviews.recordRequest(activity, requestId, review.reviewId());
        return Optional.of(
                new ScenarioReviewSnapshot(
                        review.reviewId(),
                        activity,
                        review.revision() + (moved && !created ? 1 : 0),
                        position,
                        available.map(c -> c.scenario()).orElse(null),
                        review.candidateCount()));
    }

    @Transactional
    public ScenarioReviewSnapshot next(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long expectedRevision,
            Authentication authentication) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(reviewId);
        if (expectedRevision < 1)
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_REVIEW_REVISION",
                    "유효한 검토 수정 번호가 필요합니다.");
        var input = lockAndPrepare(activityId, authentication);
        var receipt = reviews.request(activityId, requestId);
        if (receipt.isPresent()) {
            var recorded = receipt.get();
            if (!"NEXT".equals(recorded.get("request_kind")))
                throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 동작에 사용한 요청 식별자입니다.");
            if (!"ACTIVE".equals(recorded.get("outcome")))
                throw conflict("SCENARIO_REVIEW_CLOSED", "이미 종료된 검토 요청입니다.");
            if (!reviewId.equals(recorded.get("review_id"))
                    || expectedRevision != ((Number) recorded.get("expected_revision")).longValue())
                throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 요청 내용에 사용한 요청 식별자입니다.");
            // 저장된 요청을 먼저 확인해야 재전송을 낡은 수정 번호로 오인하지 않는다.
            return reviews.recordedResult(recorded);
        }
        if (reviews.isAssigned(activityId))
            throw conflict("SCENARIO_ALREADY_ASSIGNED", "이미 시나리오가 배정된 활동입니다.");
        var review =
                reviews.find(activityId)
                        .orElseThrow(
                                () -> conflict("SCENARIO_REVIEW_NOT_READY", "진행 중인 검토가 없습니다."));
        UUID instructor = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        if (!review.reviewId().equals(reviewId) || review.revision() != expectedRevision)
            throw conflict("SCENARIO_REVIEW_CHANGED", "검토가 변경되었습니다. 다시 불러와 주세요.");
        if (!review.instructorId().equals(instructor)
                || !review.inputFingerprint().equals(fingerprint(input)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "기존 검토의 입력과 일치하지 않습니다.");
        if (review.currentPosition() == null)
            throw conflict("SCENARIO_REVIEW_NO_CANDIDATE", "거절할 현재 후보가 없습니다.");
        reviews.reject(reviewId, review.currentPosition());
        var available =
                reviews.firstAvailableFrom(
                        review, input.initialDifficulty().childId(), review.currentPosition() + 1);
        Integer position = available.map(c -> c.position()).orElse(null);
        reviews.setPosition(reviewId, position, true);
        available.ifPresent(c -> reviews.recordPresentation(instructor, c.scenario().scenarioId()));
        var result =
                new ScenarioReviewSnapshot(
                        reviewId,
                        activityId,
                        review.revision() + 1,
                        position,
                        available.map(c -> c.scenario()).orElse(null),
                        review.candidateCount());
        reviews.recordNextRequest(activityId, requestId, expectedRevision, result);
        return result;
    }

    @Transactional
    public ScenarioReviewClosure cancel(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long expectedRevision,
            Authentication authentication) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(reviewId);
        if (expectedRevision < 1)
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_REVIEW_REVISION",
                    "유효한 검토 수정 번호가 필요합니다.");
        UUID instructor = lockParents(activityId, authentication);
        access.requireInstructor(
                authentication,
                reviews.childId(activityId),
                () -> new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        String requestFingerprint =
                digest("CANCEL|" + activityId + "|" + reviewId + "|" + expectedRevision);
        var receipt = reviews.request(activityId, requestId);
        if (receipt.isPresent()) {
            var recorded = receipt.get();
            if (!"CANCEL".equals(recorded.get("request_kind"))
                    || !reviewId.equals(recorded.get("review_id"))
                    || !requestFingerprint.equals(recorded.get("request_fingerprint")))
                throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 요청 내용에 사용한 요청 식별자입니다.");
            return reviews.closure(activityId, requestId);
        }
        if (reviews.isAssigned(activityId))
            throw conflict("SCENARIO_ALREADY_ASSIGNED", "이미 시나리오가 배정된 활동입니다.");
        var review =
                reviews.find(activityId)
                        .orElseThrow(
                                () -> conflict("SCENARIO_REVIEW_NOT_READY", "진행 중인 검토가 없습니다."));
        if (!review.reviewId().equals(reviewId) || review.revision() != expectedRevision)
            throw conflict("SCENARIO_REVIEW_CHANGED", "검토가 변경되었습니다. 다시 불러와 주세요.");
        if (!review.instructorId().equals(instructor))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "기존 검토의 담당 강사와 일치하지 않습니다.");
        // 입력이 달라졌거나 난이도 근거가 없어도 담당 강사는 임시 검토를 정리할 수 있다.
        Instant closedAt = Instant.now();
        reviews.closeRequests(activityId, reviewId, "CANCELLED", closedAt);
        reviews.recordCancellation(activityId, requestId, reviewId, requestFingerprint, closedAt);
        reviews.delete(activityId, reviewId);
        reviews.advanceEpoch(activityId);
        return reviews.closure(activityId, requestId);
    }

    @Transactional
    public Optional<ScenarioReviewClosure> replayConfirmation(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            Authentication authentication) {
        return replayConfirmation(
                activityId,
                requestId,
                reviewId,
                revision,
                scenarioId,
                version,
                ScenarioEdits.none(),
                authentication);
    }

    @Transactional
    public Optional<ScenarioReviewClosure> replayConfirmation(
            UUID activityId,
            UUID requestId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            ScenarioEdits edits,
            Authentication authentication) {
        lockParents(activityId, authentication);
        access.requireInstructor(
                authentication,
                reviews.childId(activityId),
                () -> new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다."));
        return confirmationReceipt(
                activityId,
                requestId,
                confirmationFingerprint(
                        activityId, reviewId, revision, scenarioId, version, edits));
    }

    private Optional<ScenarioReviewClosure> confirmationReceipt(
            UUID activityId, UUID requestId, String fingerprint) {
        var receipt = reviews.request(activityId, requestId);
        if (receipt.isEmpty()) return Optional.empty();
        var recorded = receipt.get();
        if (!"CONFIRM".equals(recorded.get("request_kind"))
                || !fingerprint.equals(recorded.get("request_fingerprint")))
            throw conflict("IDEMPOTENCY_KEY_REUSED", "다른 요청 내용에 사용한 요청 식별자입니다.");
        if (!"CONFIRMED".equals(recorded.get("outcome")))
            throw conflict("SCENARIO_REVIEW_CLOSED", "검토가 종료되었습니다.");
        return Optional.of(reviews.closure(activityId, requestId));
    }

    @Transactional
    public ScenarioReviewClosure confirm(
            ScenarioConfirmationPreparation prepared,
            UUID requestId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            Authentication authentication) {
        UUID activityId = prepared.input().initialDifficulty().activityId();
        var input = lockAndPrepare(activityId, authentication);
        UUID instructor = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        String requestFingerprint =
                confirmationFingerprint(
                        activityId, reviewId, revision, scenarioId, version, prepared.edits());
        var replay = confirmationReceipt(activityId, requestId, requestFingerprint);
        if (replay.isPresent()) return replay.get();
        if (reviews.isAssigned(activityId))
            throw conflict("SCENARIO_ALREADY_ASSIGNED", "이미 시나리오가 배정된 활동입니다.");
        if (!fingerprint(prepared.input()).equals(fingerprint(input)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "승인 확인 중 준비 입력이 변경되었습니다.");
        if (!confirmations.lockChild(input.initialDifficulty().childId()))
            throw conflict("SCENARIO_CANDIDATE_INVALID", "활동을 배정할 학생 상태가 아닙니다.");
        var review =
                reviews.find(activityId)
                        .orElseThrow(() -> conflict("SCENARIO_REVIEW_CLOSED", "검토가 종료되었습니다."));
        if (!review.instructorId().equals(instructor)
                || !review.inputFingerprint().equals(fingerprint(input)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "기존 검토의 입력과 일치하지 않습니다.");
        String source =
                confirmations
                        .currentSource(
                                activityId, reviewId, revision, scenarioId, version, instructor)
                        .orElseThrow(
                                () -> conflict("SCENARIO_REVIEW_CHANGED", "승인할 현재 후보와 일치하지 않습니다."));
        if (!source.equals(prepared.source())
                || !confirmations.lockScenario(
                        scenarioId, version, source.equals("REUSE") ? "APPROVED" : "DRAFT"))
            throw conflict("SCENARIO_CANDIDATE_INVALID", "후보 상태가 변경되었습니다.");
        String body = confirmations.contentBody(scenarioId);
        if (!body.equals(prepared.contentBody()) || !confirmations.openingReady(scenarioId))
            throw conflict("SCENARIO_CANDIDATE_INVALID", "승인 확인 중 콘텐츠가 변경되었습니다.");
        if (source.equals("REUSE") && approvals.findApproved(scenarioId).isEmpty())
            throw conflict("SCENARIO_CANDIDATE_INVALID", "공통 승인 원본을 확인할 수 없습니다.");
        boolean changed = prepared.editedContent() != null;
        UUID assignedId =
                changed
                        ? drafts.insertEdited(scenarioId, version, prepared.editedContent())
                        : scenarioId;
        int assignedVersion = changed ? Math.addExact(version, 1) : version;
        String assignedSource = changed ? "EDITED" : source;
        if (confirmations.used(input.initialDifficulty().childId(), assignedId, activityId))
            throw conflict("SCENARIO_ALREADY_USED", "학생에게 이미 배정된 시나리오입니다.");
        if (changed || source.equals("GENERATED")) {
            confirmations.approveDraft(assignedId);
            approvals.recordApproval(assignedId, instructor, prepared.conditions());
        }
        Instant approvedAt = Instant.now();
        confirmations.assign(activityId, assignedId, assignedSource);
        confirmations.recordAssignment(
                activityId,
                reviewId,
                instructor,
                assignedId,
                assignedVersion,
                input.initialDifficulty().scenarioLevel(),
                digest(confirmations.contentBody(assignedId)),
                approvedAt);
        if (!changed && source.equals("REUSE"))
            confirmations.recordReuse(instructor, scenarioId, approvedAt);
        reviews.closeRequests(activityId, reviewId, "CONFIRMED", approvedAt);
        reviews.recordConfirmation(activityId, requestId, reviewId, requestFingerprint, approvedAt);
        reviews.delete(activityId, reviewId);
        reviews.advanceEpoch(activityId);
        return reviews.closure(activityId, requestId);
    }

    private String confirmationFingerprint(
            UUID activityId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            ScenarioEdits edits) {
        return digest(
                "CONFIRM|"
                        + activityId
                        + "|"
                        + reviewId
                        + "|"
                        + revision
                        + "|"
                        + scenarioId
                        + "|"
                        + version
                        + (edits.isEmpty() ? "" : "|EDITS:v1|" + json.writeValueAsString(edits)));
    }

    @Transactional
    public ScenarioGenerationContext prepareGeneration(
            ScenarioReviewSnapshot expected, Authentication authentication) {
        var input = lockAndPrepare(expected.activityId(), authentication);
        var review =
                reviews.find(expected.activityId())
                        .orElseThrow(() -> conflict("SCENARIO_REVIEW_CLOSED", "검토가 종료되었습니다."));
        validateGenerationReview(expected, input, review, authentication);
        var available = reviews.firstAvailable(review, input.initialDifficulty().childId());
        var current =
                new ScenarioReviewSnapshot(
                        review.reviewId(),
                        review.activityId(),
                        review.revision(),
                        available.map(c -> c.position()).orElse(null),
                        available.map(c -> c.scenario()).orElse(null),
                        review.candidateCount());
        return new ScenarioGenerationContext(
                input,
                current,
                reviews.epoch(expected.activityId()),
                current.candidate() == null
                        ? drafts.lastRejectedGenerated(review.reviewId())
                        : null);
    }

    @Transactional
    public ScenarioReviewSnapshot saveGenerated(
            ScenarioGenerationContext context,
            GeneratedScenario generated,
            Authentication authentication) {
        var expected = context.snapshot();
        var input = lockAndPrepare(expected.activityId(), authentication);
        if (context.epoch() != reviews.epoch(expected.activityId())
                || !fingerprint(context.input()).equals(fingerprint(input)))
            throw conflict("SCENARIO_REVIEW_CHANGED", "생성 중 검토 입력이나 상태가 변경되었습니다.");
        var review =
                reviews.find(expected.activityId())
                        .orElseThrow(() -> conflict("SCENARIO_REVIEW_CLOSED", "검토가 종료되었습니다."));
        validateGenerationReview(expected, input, review, authentication);
        var available = reviews.firstAvailable(review, input.initialDifficulty().childId());
        if (available.isPresent()) {
            var selected = available.get();
            return new ScenarioReviewSnapshot(
                    review.reviewId(),
                    review.activityId(),
                    review.revision(),
                    selected.position(),
                    selected.scenario(),
                    review.candidateCount());
        }
        UUID id = drafts.insert(generated);
        int position = reviews.appendGenerated(review.reviewId(), id);
        reviews.setPosition(review.reviewId(), position, false);
        reviews.recordPresentation(review.instructorId(), id);
        var result =
                new ScenarioReviewSnapshot(
                        review.reviewId(),
                        review.activityId(),
                        review.revision(),
                        position,
                        new ScenarioVersionRef(id, 1),
                        review.candidateCount() + 1);
        reviews.completeGenerationRequests(result);
        return result;
    }

    private void validateGenerationReview(
            ScenarioReviewSnapshot expected,
            InitialScenarioPreparation input,
            com.neuringo.neuringobe.scenario.dto.ScenarioReviewState review,
            Authentication authentication) {
        if (reviews.isAssigned(expected.activityId()))
            throw conflict("SCENARIO_ALREADY_ASSIGNED", "이미 시나리오가 배정된 활동입니다.");
        if (!review.reviewId().equals(expected.reviewId())
                || review.revision() != expected.revision())
            throw conflict("SCENARIO_REVIEW_CHANGED", "검토가 변경되었습니다. 다시 불러와 주세요.");
        if (!review.instructorId()
                        .equals(((AuthenticatedUser) authentication.getPrincipal()).userId())
                || !review.inputFingerprint().equals(fingerprint(input)))
            throw conflict("SCENARIO_REVIEW_INPUT_CHANGED", "기존 검토의 입력과 일치하지 않습니다.");
    }

    private InitialScenarioPreparation lockAndPrepare(
            UUID activity, Authentication authentication) {
        lockParents(activity, authentication);
        return preparations.prepare(activity, authentication);
    }

    private UUID lockParents(UUID activity, Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
        }
        // 탈퇴는 계정부터 잠그므로 FK 확인도 같은 순서로 진행한다.
        if (!reviews.lockInstructor(user.userId()) || !reviews.lockActivity(activity)) {
            throw new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
        }
        return user.userId();
    }

    private String fingerprint(InitialScenarioPreparation input) {
        return ScenarioInputFingerprint.of(input);
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
