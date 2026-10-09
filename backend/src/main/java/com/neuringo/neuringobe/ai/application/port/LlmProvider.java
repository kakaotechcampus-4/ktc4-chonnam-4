package com.neuringo.neuringobe.ai.application.port;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.model.LlmRequest;

public interface LlmProvider {

    /** Honor a supplied local call budget and perform one attempt; orchestration owns retries. */
    AiCallResult<LlmCompletion> complete(LlmRequest request);
}
