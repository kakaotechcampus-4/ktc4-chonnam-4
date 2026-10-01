package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.neuringo.neuringobe.ai.application.model.AiCallMetadata;
import com.neuringo.neuringobe.ai.application.model.AiOperation;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** STT·TTS 어댑터가 함께 쓰는 HTTP 설정과 지표 생성. 자동 재시도는 하지 않는다(재호출은 오케스트레이터 책임). */
final class SpeechHttp {

    private SpeechHttp() {}

    static RestClient restClient(String baseUrl, Duration requestTimeout) {
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(requestTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(requestTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    static AiCallMetadata metadata(
            UUID requestId,
            AiOperation operation,
            String provider,
            String model,
            long startedAt,
            int attemptNo,
            Integer inputTokens,
            Integer outputTokens) {
        return new AiCallMetadata(
                requestId,
                operation,
                provider,
                model,
                null,
                null,
                null,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                attemptNo,
                inputTokens,
                outputTokens,
                null);
    }
}
