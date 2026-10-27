package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.quiz.service.FirstQuizCompleted;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 첫 활동에 역할극 시나리오를 붙인다. 퀴즈 저장이 확정된 뒤 새 트랜잭션에서 돌아서, 여기서 실패해도 아이의 퀴즈 결과는 남는다. 붙지 않은 활동은 퀴즈 결과를 다시 읽을 때
 * 다시 시도한다. 나중에 시간이 걸리는 AI 생성을 붙여도 퀴즈 저장과 묶이지 않는다.
 */
@Service
public class ScenarioAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioAttachmentService.class);

    private final ScenarioRepository scenarios;
    private final ActivityRepository activities;
    private final TransactionTemplate tx;

    public ScenarioAttachmentService(
            ScenarioRepository scenarios,
            ActivityRepository activities,
            PlatformTransactionManager transactionManager) {
        this.scenarios = scenarios;
        this.activities = activities;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFirstQuizCompleted(FirstQuizCompleted event) {
        try {
            Optional<UUID> attached = tx.execute(status -> attach(event));
            if (attached.isEmpty()) {
                log.info(
                        "scenario not attached activityId={} level={}",
                        event.activityId(),
                        event.scenarioLevel());
            }
        } catch (RuntimeException ex) {
            // 커밋 뒤라 퀴즈 응답은 이미 저장됐다. 예외를 올리면 아이 화면에 실패로 보이므로 남기기만 하고, 다음 결과 조회 때 다시 시도한다.
            log.warn("scenario attach failed activityId={}", event.activityId(), ex);
        }
    }

    /** 이미 시나리오가 붙은 활동은 건드리지 않는다. 같은 알림이 두 번 와도 하나만 붙는다. */
    private Optional<UUID> attach(FirstQuizCompleted event) {
        Optional<UUID> picked =
                scenarios.findFirstUnusedApproved(event.scenarioLevel(), event.childId());
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        int updated = activities.attachScenarioIfEmpty(event.activityId(), picked.get());
        return updated == 1 ? picked : Optional.empty();
    }
}
