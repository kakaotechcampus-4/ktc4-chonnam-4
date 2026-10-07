package com.neuringo.neuringobe.ai.application.port;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;

public interface TextToSpeechProvider {

    AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request);
}
