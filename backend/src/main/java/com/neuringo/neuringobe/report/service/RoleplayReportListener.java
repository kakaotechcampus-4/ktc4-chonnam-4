package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.roleplay.service.RoleplayActivityCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 역할극 완료가 커밋된 뒤 리포트를 준비하고 생성 작업에 넣는다. ScenarioAttachmentService 와 같은 방식으로, 실패해도 이미 확정된 역할극 완료를 실패
 * 응답으로 바꾸지 않는다.
 *
 * <p>준비(1단계)는 DB 만 쓰는 짧은 작업이라 완료를 처리한 스레드에서 바로 한다. LLM 을 부르는 생성(2단계)은 생성 전용 스레드로 넘긴다. 서버가 그 사이에
 * 꺼지거나 대기열이 넘쳐 PENDING 으로 남은 리포트는 5분마다 도는 재처리가 다시 넣는다.
 */
@Service
public class RoleplayReportListener {
    private static final Logger log = LoggerFactory.getLogger(RoleplayReportListener.class);

    private final ReportPreparationService preparation;
    private final ReportGenerationService generation;

    public RoleplayReportListener(
            ReportPreparationService preparation, ReportGenerationService generation) {
        this.preparation = preparation;
        this.generation = generation;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoleplayCompleted(RoleplayActivityCompleted event) {
        try {
            preparation.prepare(event.activityId()).ifPresent(generation::submit);
        } catch (RuntimeException failure) {
            log.warn(
                    "리포트 준비 실패 activityId={} type={}",
                    event.activityId(),
                    failure.getClass().getSimpleName());
        }
    }

    @Scheduled(
            initialDelayString = "${neuringo.report.sweep-initial-delay:PT1M}",
            fixedDelayString = "${neuringo.report.sweep-interval:PT5M}")
    public void resubmitStale() {
        try {
            generation.resubmitStale();
        } catch (RuntimeException failure) {
            log.warn("리포트 재처리 실패 type={}", failure.getClass().getSimpleName());
        }
    }
}
