package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailure;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.port.TextToSpeechProvider;

/**
 * 로컬 끝까지 돌려 보기 전용 TTS(neuringo.ai.tts.provider=silent). 음성을 만들지 않고 다시 시도하지 않는 실패를 돌려준다. 역할극 응답은 글로만
 * 전달되고(speech.available=false), 턴 처리는 그대로 이어진다.
 */
public final class SilentTextToSpeechProvider implements TextToSpeechProvider {

    @Override
    public AiCallResult<SynthesizedSpeech> synthesize(SpeechSynthesisRequest request) {
        return new AiCallResult.Failure<>(
                new AiFailure(AiFailureType.PROVIDER_REQUEST_REJECTED, "silent"),
                new AiCallMetadata(
                        request.traceContext().requestId(),
                        AiOperation.SPEECH_SYNTHESIS,
                        "silent",
                        "silent-tts",
                        null,
                        null,
                        null,
                        0,
                        request.currentAttempt(),
                        null,
                        null,
                        null));
    }
}
