package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscription;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import com.neuringo.neuringobe.ai.application.port.SpeechToTextProvider;

/**
 * STT 를 켜지 않았을 때(neuringo.ai.stt.provider=none) 쓰는 자리 채움. 음성을 글자로 바꾸지 않고 재시도하지 않는 실패를 돌려준다.
 *
 * <p>역할극은 이 실패를 "다시 말해 줘"(재입력)로 처리하므로 음성 답은 받지 않고 글자 답만 대화가 이어진다.
 */
public final class DisabledSpeechToTextProvider implements SpeechToTextProvider {

    static final String PROVIDER = "none";
    static final String ERROR_CODE = "STT_DISABLED";

    @Override
    public AiCallResult<SpeechTranscription> transcribe(SpeechTranscriptionRequest request) {
        return new AiCallResult.Failure<>(
                new AiFailure(AiFailureType.PROVIDER_REQUEST_REJECTED, ERROR_CODE),
                SpeechHttp.metadata(
                        request.traceContext().requestId(),
                        AiOperation.SPEECH_TRANSCRIPTION,
                        PROVIDER,
                        PROVIDER,
                        System.nanoTime(),
                        request.currentAttempt(),
                        null,
                        null));
    }
}
