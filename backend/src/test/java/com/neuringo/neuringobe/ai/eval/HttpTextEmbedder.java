package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.report.application.similarity.TextEmbedder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 호환 /embeddings 를 부르는 측정용 임베딩. 운영 어댑터(Spring AI EmbeddingModel)를 만들기 전에 유사도 기준값을 재 보려고 둔다.
 * 같은 문장은 한 번만 부른다. 오류 응답 본문은 출력하지 않는다.
 */
final class HttpTextEmbedder implements TextEmbedder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Map<String, float[]> cache = new LinkedHashMap<>();
    private final AtomicInteger promptTokens = new AtomicInteger();

    HttpTextEmbedder(String baseUrl, String apiKey, String model) {
        String base = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/embeddings");
        this.apiKey = Objects.requireNonNull(apiKey);
        this.model = Objects.requireNonNull(model);
    }

    @Override
    public synchronized float[] embed(String text) {
        return cache.computeIfAbsent(text, this::call);
    }

    int promptTokens() {
        return promptTokens.get();
    }

    String model() {
        return model;
    }

    private float[] call(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> response;
        try {
            response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "embedding call failed: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding call interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "embedding call returned HTTP " + response.statusCode());
        }
        JsonNode root = JSON.readTree(response.body());
        JsonNode usage = root.get("usage");
        if (usage != null && usage.has("prompt_tokens")) {
            promptTokens.addAndGet(usage.get("prompt_tokens").asInt());
        }
        JsonNode values = root.get("data").get(0).get("embedding");
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = (float) values.get(i).asDouble();
        return vector;
    }
}
