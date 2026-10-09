package com.neuringo.neuringobe.ai.application.port;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;

public interface SpeechToTextProvider {

    /** Honor a supplied local call budget; this method performs one transcription attempt. */
    AiCallResult<SpeechTranscription> transcribe(SpeechTranscriptionRequest request);
}
