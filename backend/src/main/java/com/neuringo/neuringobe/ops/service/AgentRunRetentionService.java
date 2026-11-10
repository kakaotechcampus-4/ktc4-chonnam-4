package com.neuringo.neuringobe.ops.service;

import com.neuringo.neuringobe.ops.config.AgentRunProperties;
import com.neuringo.neuringobe.ops.repository.AgentRunStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 보관 기간이 지난 실행 기록을 한 시간마다 지운다. 단계는 외래 키 CASCADE 로 함께 지워진다. */
@Service
@ConditionalOnProperty(name = "neuringo.agent-runs.enabled", havingValue = "true")
public class AgentRunRetentionService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRetentionService.class);

    private final AgentRunStore store;
    private final AgentRunRecordingService recording;
    private final AgentRunProperties properties;
    private final Clock clock;

    public AgentRunRetentionService(
            AgentRunStore store,
            AgentRunRecordingService recording,
            AgentRunProperties properties,
            Clock clock) {
        this.store = store;
        this.recording = recording;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    public void purge() {
        try {
            int deleted = store.deleteStartedBefore(clock.instant().minus(properties.retention()));
            int abandoned = recording.discardAbandoned();
            if (deleted > 0 || abandoned > 0)
                log.info("AGENT_RUN_PURGED deleted={} abandoned={}", deleted, abandoned);
        } catch (RuntimeException failure) {
            log.warn("AGENT_RUN_PURGE_FAILED type={}", failure.getClass().getSimpleName());
        }
    }
}
