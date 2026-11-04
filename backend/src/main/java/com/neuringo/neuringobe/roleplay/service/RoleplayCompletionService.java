package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.activity.service.ActivityCompletionService;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator.Decision;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import com.neuringo.neuringobe.roleplay.config.RoleplayProgressProperties;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 서버의 활동 목표 판정에 따라 턴·관찰·완료·보상·기록·발화 삭제를 함께 확정한다. */
@Service
public class RoleplayCompletionService {
    private final JpaRoleplayCheckpointStore checkpoints;
    private final ActivityRepository activities;
    private final QuizResultRepository quizzes;
    private final ActivityCompletionService completion;
    private final RoleplayCanonicalRetentionService retention;
    private final RoleplayGoalObservationStore observations;
    private final RoleplayProgressProperties limits;
    private final TransactionTemplate transaction;

    public RoleplayCompletionService(
            JpaRoleplayCheckpointStore checkpoints,
            ActivityRepository activities,
            QuizResultRepository quizzes,
            ActivityCompletionService completion,
            RoleplayCanonicalRetentionService retention,
            RoleplayGoalObservationStore observations,
            PlatformTransactionManager transactions,
            RoleplayProgressProperties limits) {
        this.checkpoints = checkpoints;
        this.activities = activities;
        this.quizzes = quizzes;
        this.completion = completion;
        this.retention = retention;
        this.observations = observations;
        this.limits = Objects.requireNonNull(limits);
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 외부 호출을 마친 서버 내부 경로에서 사용한다. 화면의 완료 표시나 마무리 문장을 판정값으로 쓰지 않는다. */
    public RoleplayCheckpointStore.CommitResult commitApprovedTurn(
            RoleplayCheckpointCommand command,
            RoleplayGoalObservationPlan plan,
            RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(deadline);
        var scope = plan.proposal().scope();
        var goalProgress = plan.proposal().inputGoalProgress();
        if (goalProgress != null
                && (command.inputContext() == null
                        || !goalProgress
                                .microGoalId()
                                .equals(command.inputContext().microGoalId()))) {
            return new RoleplayCheckpointStore.Conflict();
        }
        if (!scope.sessionId().equals(command.sessionId())
                || !scope.scenarioId().equals(command.scenarioId())
                || scope.scenarioVersion() != command.scenarioVersion()
                || !scope.turnId().equals(command.turnId())
                || !scope.candidateId().equals(command.candidateId())
                || scope.checkpointVersion() != command.expectedVersion()
                || !plan.proposal().nextFocusGoalId().equals(command.microGoalId())) {
            return new RoleplayCheckpointStore.Conflict();
        }
        try {
            deadline.requireActive();
            return transaction.execute(
                    status -> {
                        var result = checkpoints.commitInCurrentTransaction(command, deadline);
                        if (result instanceof RoleplayCheckpointStore.Committed saved
                                && !saved.replayed()) {
                            var activity =
                                    activities
                                            .findByIdForUpdate(command.activityId())
                                            .orElseThrow();
                            var type = activity.getActivityType();
                            if ((type != ActivityType.FIRST_COMBINED
                                            && type != ActivityType.ROLEPLAY)
                                    || (type == ActivityType.FIRST_COMBINED
                                            && !quizzes.existsById(command.activityId()))) {
                                status.setRollbackOnly();
                                return new RoleplayCheckpointStore.Conflict();
                            }
                            boolean maximumTurnReached =
                                    maximumLearningTurnReached(command, plan, deadline);
                            if (maximumTurnReached
                                    && (!"CLOSE_ROLEPLAY".equals(command.strategyUsed())
                                            || !"CLOSING".equals(command.responseType()))) {
                                status.setRollbackOnly();
                                return new RoleplayCheckpointStore.Conflict();
                            }
                            observations.save(plan);
                            if (plan.proposal().decision() == Decision.ACHIEVED
                                    || maximumTurnReached) {
                                completion.completeAfterRoleplay(activity);
                                retention.completeNormallyAndDelete(
                                        command.sessionId(),
                                        command.childId(),
                                        command.activityId());
                            }
                        }
                        deadline.requireActive();
                        return result;
                    });
        } catch (RoleplayTurnDeadline.Expired expired) {
            return new RoleplayCheckpointStore.Expired();
        } catch (RuntimeException failure) {
            if (deadline.remainingNanos() == 0) return new RoleplayCheckpointStore.Expired();
            throw failure;
        }
    }

    private boolean maximumLearningTurnReached(
            RoleplayCheckpointCommand command,
            RoleplayGoalObservationPlan plan,
            RoleplayTurnDeadline deadline) {
        if (!limits.configured()) return false;
        var progress = plan.proposal().inputGoalProgress();
        // 입력 판정 계약 도입 전 내부 호출은 기존 상한을 유지한다.
        if (progress == null) return command.turnNumber() >= limits.maximumTurnCount();
        if (progress.learningState().equals("NOT_ASSESSABLE")) return false;
        var learningTurn =
                observations.nextLearningTurn(command.sessionId(), command.turnNumber(), deadline);
        return learningTurn.isPresent() && learningTurn.getAsInt() >= limits.maximumTurnCount();
    }
}
