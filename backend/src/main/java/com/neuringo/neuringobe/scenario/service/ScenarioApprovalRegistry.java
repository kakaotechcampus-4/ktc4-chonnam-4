package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ScenarioApprovalRecord;
import com.neuringo.neuringobe.scenario.repository.ScenarioApprovalRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioApprovalRegistry {
    private final ScenarioApprovalRepository approvals;

    public ScenarioApprovalRegistry(ScenarioApprovalRepository approvals) {
        this.approvals = approvals;
    }

    /** 권한·콘텐츠 검사를 마친 최종 배정 트랜잭션에서만 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ScenarioApprovalRecord recordApproval(
            UUID scenarioId, UUID instructorId, ScenarioApprovalConditions conditions) {
        Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(instructorId);
        Objects.requireNonNull(conditions);
        if (!approvals.lockApprovedScenario(scenarioId, conditions.scenarioLevel())) {
            throw new IllegalStateException("동일 난이도의 승인된 시나리오가 필요합니다.");
        }
        var existing = approvals.find(scenarioId);
        if (existing.isPresent()) {
            var approval = existing.get();
            if (!Objects.equals(approval.instructorId(), instructorId)
                    || !approval.conditions().equals(conditions)) {
                throw new IllegalStateException("기존 승인자나 확인한 조건을 변경할 수 없습니다.");
            }
            return approval;
        }
        return approvals.insert(scenarioId, instructorId, conditions);
    }

    @Transactional(readOnly = true)
    public Optional<ScenarioApprovalRecord> findApproved(UUID scenarioId) {
        return approvals.findApproved(scenarioId);
    }

    @Transactional(readOnly = true)
    public Optional<ScenarioApprovalRecord> findOwnedApproved(UUID scenarioId, UUID instructorId) {
        return approvals.findOwnedApproved(scenarioId, instructorId);
    }
}
