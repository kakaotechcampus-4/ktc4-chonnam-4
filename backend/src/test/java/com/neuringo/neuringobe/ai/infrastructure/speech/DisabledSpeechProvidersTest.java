package com.neuringo.neuringobe.ai.infrastructure.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiFailureType;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SpeechSynthesisRequest;
import com.neuringo.neuringobe.ai.application.model.SpeechTranscriptionRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisabledSpeechProvidersTest {

    private final AiTraceContext trace =
            new AiTraceContext(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null,
                    null,
                    null,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null);

    @Test
    void transcriptionFailsWithoutRetrySoVoiceAnswersBecomeReinput() {
        var result =
                new DisabledSpeechToTextProvider()
                        .transcribe(
                                new SpeechTranscriptionRequest(
                                        trace, 1, new byte[] {1, 2, 3}, AudioFormat.WAV));

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        var failure = (AiCallResult.Failure<?>) result;
        assertThat(failure.failure().type()).isEqualTo(AiFailureType.PROVIDER_REQUEST_REJECTED);
        assertThat(failure.failure().retryable()).isFalse();
        assertThat(failure.metadata().operation()).isEqualTo(AiOperation.SPEECH_TRANSCRIPTION);
        assertThat(failure.metadata().requestId()).isEqualTo(trace.requestId());
    }

    @Test
    void synthesisFailsWithoutRetrySoRepliesStayTextOnly() {
        var result =
                new DisabledTextToSpeechProvider()
                        .synthesize(new SpeechSynthesisRequest(trace, 1, "안녕", null, null));

        assertThat(result).isInstanceOf(AiCallResult.Failure.class);
        var failure = (AiCallResult.Failure<?>) result;
        assertThat(failure.failure().retryable()).isFalse();
        assertThat(failure.metadata().operation()).isEqualTo(AiOperation.SPEECH_SYNTHESIS);
    }
}
