package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.domain.ActivityStatus;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import com.neuringo.neuringobe.quiz.service.FirstQuizCompleted;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScenarioAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioAttachmentService.class);
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final QuizResultRepository results;
    private final LearningGoalRepository goals;
    private final ScenarioRepository scenarios;
    private final TransactionTemplate tx;

    public ScenarioAttachmentService(
            ActivityRepository activities,
            ChildRepository children,
            QuizResultRepository results,
            LearningGoalRepository goals,
            ScenarioRepository scenarios,
            PlatformTransactionManager transactionManager) {
        this.activities = activities;
        this.children = children;
        this.results = results;
        this.goals = goals;
        this.scenarios = scenarios;
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFirstQuizCompleted(FirstQuizCompleted event) {
        try {
            tx.executeWithoutResult(status -> attach(event));
        } catch (RuntimeException exception) {
            // 연결 실패로 이미 확정된 퀴즈를 실패 응답으로 바꾸지 않는다.
            log.warn("시나리오 연결 실패 activityId={}", event.activityId());
        }
    }

    private void attach(FirstQuizCompleted event) {
        var activity = activities.findByIdForUpdate(event.activityId()).orElse(null);
        if (activity == null
                || activity.getActivityType() != ActivityType.FIRST_COMBINED
                || !activity.getChildId().equals(event.childId())
                || activity.getScenarioId() != null
                || activity.getStatus() == ActivityStatus.COMPLETED) {
            return;
        }
        // 기존 퀴즈 저장·삭제와 같은 활동 → 아동 잠금 순서를 지킨다.
        var child = children.findByIdForUpdate(activity.getChildId()).orElse(null);
        if (child == null || child.getStatus() != ChildStatus.ACTIVE) {
            return;
        }
        var evidence =
                results.findAppliedInitialDifficulty(activity.getActivityId(), child.getChildId())
                        .orElse(null);
        if (evidence == null || !evidence.scenarioLevel().equals(event.scenarioLevel())) {
            return;
        }
        var goal = goals.findById(evidence.goalId()).orElse(null);
        if (goal == null
                || !goal.getChildId().equals(child.getChildId())
                || goal.getContentHash() == null
                || goal.getContentHash().isBlank()) {
            return;
        }
        scenarios
                .findFirstUnusedApproved(
                        child.getClassId(),
                        goal.getContentHash(),
                        evidence.scenarioLevel(),
                        child.getChildId())
                .ifPresent(
                        scenarioId ->
                                activities.attachScenarioIfEmpty(
                                        activity.getActivityId(), scenarioId));
    }
}
