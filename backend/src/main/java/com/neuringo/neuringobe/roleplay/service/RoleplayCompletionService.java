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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버의 활동 목표 판정에 따라 턴·관찰·완료·보상·기록·발화 삭제를 함께 확정한다. 활동이 이번 턴으로 처음 완료되면 {@link
 * RoleplayActivityCompleted} 를 발행한다. 리포트·다음 난이도·추천은 커밋 뒤 그 이벤트를 받아 따로 만든다.
 */
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
    private final ApplicationEventPublisher events;

    public RoleplayCompletionService(
            JpaRoleplayCheckpointStore checkpoints,
            ActivityRepository activities,
            QuizResultRepository quizzes,
            ActivityCompletionService completion,
            RoleplayCanonicalRetentionService retention,
            RoleplayGoalObservationStore observations,
            PlatformTransactionManager transactions,
            RoleplayProgressProperties limits,
            ApplicationEventPublisher events) {
        this.checkpoints = checkpoints;
        this.activities = activities;
        this.quizzes = quizzes;
        this.completion = completion;
        this.retention = retention;
        this.observations = observations;
        this.limits = Objects.requireNonNull(limits);
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.events = Objects.requireNonNull(events);
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
                            // 저장소가 잠긴 세션의 다음 턴 번호를 이미 대조했다.
                            boolean maximumTurnReached =
                                    limits.configured()
                                            && command.turnNumber() >= limits.maximumTurnCount();
                            if (maximumTurnReached
                                    && (!"CLOSE_ROLEPLAY".equals(command.strategyUsed())
                                            || !"CLOSING".equals(command.responseType()))) {
                                status.setRollbackOnly();
                                return new RoleplayCheckpointStore.Conflict();
                            }
                            observations.save(plan);
                            if (plan.proposal().decision() == Decision.ACHIEVED
                                    || maximumTurnReached) {
                                boolean firstCompletion =
                                        completion.completeAfterRoleplay(activity);
                                retention.completeNormallyAndDelete(
                                        command.sessionId(),
                                        command.childId(),
                                        command.activityId());
                                if (firstCompletion) {
                                    // 리스너는 커밋 뒤(AFTER_COMMIT)에만 돈다. 롤백되면 이벤트도 사라진다.
                                    events.publishEvent(
                                            new RoleplayActivityCompleted(
                                                    command.activityId(),
                                                    command.childId(),
                                                    command.sessionId()));
                                }
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
}
