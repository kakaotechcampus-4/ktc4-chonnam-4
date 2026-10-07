package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted request-scoped boundary for an approved candidate's transient audio. No default storage
 * or retention policy.
 */
public interface RoleplaySpeechDelivery {
    /**
     * Adapter must bind access to the authenticated recipient; expected unavailability returns
     * empty. Never expose vendor credentials.
     */
    Optional<String> publish(UUID approvedCandidateId, SynthesizedSpeech speech);
}
