package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;

/**
 * TTS 를 켜지 않았을 때(neuringo.ai.tts.provider=none) 쓰는 자리 채움. 재시도하지 않는 실패를 돌려준다.
 *
 * <p>역할극은 음성 합성 실패를 "소리 없이 글자만 전달"로 처리하므로 대화는 그대로 이어진다.
 */
public final class DisabledTextToSpeechProvider implements TextToSpeechProvider {

    static final String PROVIDER = "none";
    static final String ERROR_CODE = "TTS_DISABLED";

    @Override
    public AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request) {
        return new AiCallResult.Failure<>(
                new AiFailure(AiFailureType.PROVIDER_REQUEST_REJECTED, ERROR_CODE),
                SpeechHttp.metadata(
                        request.traceContext().requestId(),
                        AiOperation.SPEECH_SYNTHESIS,
                        PROVIDER,
                        PROVIDER,
                        System.nanoTime(),
                        request.currentAttempt(),
                        null,
                        null));
    }
}
