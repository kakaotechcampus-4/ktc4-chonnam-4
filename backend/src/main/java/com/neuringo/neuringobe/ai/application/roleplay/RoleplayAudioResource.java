package com.neuringo.neuringobe.ai.application.roleplay;

import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;

/** Caller transfers exclusive ownership of a disposable upload spool, not a retention copy. */
public interface RoleplayAudioResource extends AutoCloseable {
    SpeechTranscriptionRequest request();

    @Override
    void close();
}
