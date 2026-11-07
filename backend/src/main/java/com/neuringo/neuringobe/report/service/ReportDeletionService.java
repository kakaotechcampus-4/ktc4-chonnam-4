package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.report.repository.LearningReportStore;
import com.neuringo.neuringobe.scenario.service.FollowUpDifficultyService;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 아동 삭제(ADR 2026-10-04 D1) 때 리포트·추천·다음 난이도를 지운다. DeletionService 가 학습 기록·활동보다 먼저 부른다. */
@Service
public class ReportDeletionService {
    private final LearningReportStore reports;
    private final FollowUpDifficultyService difficulties;

    public ReportDeletionService(
            LearningReportStore reports, FollowUpDifficultyService difficulties) {
        this.reports = reports;
        this.difficulties = difficulties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByChildIds(Collection<UUID> childIds) {
        reports.deleteByChildIds(childIds);
        difficulties.deleteByChildIds(childIds);
    }
}
