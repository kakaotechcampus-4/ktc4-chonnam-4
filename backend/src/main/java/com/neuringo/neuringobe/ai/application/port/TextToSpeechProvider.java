package com.neuringo.neuringobe.ai.application.port;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;

public interface TextToSpeechProvider {

    /** Honor a supplied local call budget; this method performs one synthesis attempt. */
    AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request);
}
