package com.neuringo.neuringobe.ai.eval;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 실제 ML API 실측 설정. {@code -Pmlapi.<키>}(Gradle 이 시스템 속성으로 넘김) → 환경변수 {@code MLAPI_<키>} → 기본값 순서로
 * 읽는다. 모델 주소·ID·가격과 키는 backend/.env.local 에서 Gradle 이 넣은 환경변수를 쓴다. 값은 출력하지 않는다.
 *
 * @param alias .env.local 의 MODEL_&lt;alias&gt;_* 이름(예: MINI41, NANO54, HAIKU55)
 * @param baseMode v1(모델 주소 그대로) 또는 no-v1(주소 끝 /v1 을 뗌 — application.yml 기본값 규칙을 흉내 냄)
 * @param timeout 호출 하나의 제한(AI_TIMEOUT 과 같은 뜻). 기본값은 배포 기본값 10s
 */
record RealMlApiSettings(
        String alias,
        String modelId,
        String modelUrl,
        double priceInputPerMillion,
        double priceOutputPerMillion,
        String baseMode,
        String timeout,
        List<String> personas,
        int repeats,
        double budgetKrw,
        String runId,
        String sttBaseUrl,
        String sttModel,
        Path voiceDir,
        String apiKey) {

    private static volatile RealMlApiSettings loaded;

    /** 한 실행 안에서는 같은 값(특히 runId)을 쓰도록 한 번만 읽는다. */
    static RealMlApiSettings load() {
        RealMlApiSettings current = loaded;
        if (current != null) return current;
        synchronized (RealMlApiSettings.class) {
            if (loaded == null) loaded = read();
            return loaded;
        }
    }

    private static RealMlApiSettings read() {
        String alias = setting("model", "MINI41").toUpperCase(Locale.ROOT);
        String[] price = env("MODEL_" + alias + "_PRICE", "0,0").split(",");
        String voiceDir = setting("voiceDir", "");
        return new RealMlApiSettings(
                alias,
                requireEnv("MODEL_" + alias + "_ID"),
                requireEnv("MODEL_" + alias + "_URL"),
                Double.parseDouble(price[0].trim()),
                Double.parseDouble(price.length > 1 ? price[1].trim() : "0"),
                setting("base", "v1"),
                setting("timeout", "10s"),
                Arrays.stream(setting("personas", "steady,strong,unsure").split(","))
                        .map(String::trim)
                        .filter(x -> !x.isEmpty())
                        .toList(),
                Integer.parseInt(setting("repeats", "1")),
                Double.parseDouble(setting("budget", "3000")),
                setting(
                        "run",
                        RealMlApiRoleplayIntegrationTest.timestamp()
                                + "-"
                                + alias.toLowerCase(Locale.ROOT)),
                env("MODEL_WHISPER_URL", "https://api.openai.com"),
                env("MODEL_WHISPER_ID", "gpt-4o-mini-transcribe"),
                voiceDir.isBlank() ? null : Path.of(voiceDir),
                requireEnv("AI_API_KEY"));
    }

    /** spring.ai.openai.base-url 에 넣을 값. Spring AI 는 이 값 뒤에 /chat/completions 만 붙인다. */
    String chatBaseUrl() {
        if ("no-v1".equals(baseMode) && modelUrl.endsWith("/v1")) {
            return modelUrl.substring(0, modelUrl.length() - 3);
        }
        return modelUrl;
    }

    private static String setting(String key, String fallback) {
        String property = System.getProperty("mlapi." + key);
        if (property != null && !property.isBlank()) return property.trim();
        String variable = System.getenv("MLAPI_" + key.toUpperCase(Locale.ROOT));
        if (variable != null && !variable.isBlank()) return variable.trim();
        return fallback;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set (backend/.env.local)");
        }
        return value.trim();
    }

    @Override
    public String toString() {
        return "RealMlApiSettings[alias=" + alias + ", model=" + modelId + ", run=" + runId + "]";
    }
}
