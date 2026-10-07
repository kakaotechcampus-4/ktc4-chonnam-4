package com.neuringo.neuringobe.roleplay.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "roleplay.retention.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RoleplayCanonicalRetentionJob {
    private final RoleplayCanonicalRetentionService retention;

    public RoleplayCanonicalRetentionJob(RoleplayCanonicalRetentionService retention) {
        this.retention = retention;
    }

    /** Cadence is configurable; the 24-hour eligibility cutoff is fixed by DEC-039. */
    @Scheduled(
            fixedDelayString = "${roleplay.retention.cleanup-delay-ms:60000}",
            initialDelayString = "${roleplay.retention.cleanup-delay-ms:60000}")
    public void purgeExpiredCanonicalUtterances() {
        retention.purgeExpiredCanonicalUtterances();
    }
}
