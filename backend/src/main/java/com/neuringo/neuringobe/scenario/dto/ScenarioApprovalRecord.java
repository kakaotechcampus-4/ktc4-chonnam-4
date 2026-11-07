package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import java.time.Instant;
import java.util.UUID;

public record ScenarioApprovalRecord(
        UUID scenarioId,
        UUID instructorId,
        ScenarioApprovalConditions conditions,
        int verificationRuleVersion,
        Instant approvedAt) {}
