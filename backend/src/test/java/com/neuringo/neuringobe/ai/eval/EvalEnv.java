package com.neuringo.neuringobe.ai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 측정 실행기의 설정값. 셸 환경변수에 값이 있으면 그것을, 없으면 backend/.env.local 의 KEY=VALUE 를 쓴다. 값은 출력하지 않는다.
 *
 * <p>roleplayEval 은 Gradle 이 .env.local 을 환경변수로 넣어 주지만, reportEval 은 이 클래스가 직접 읽어서 build.gradle 에
 * 같은 코드를 또 두지 않는다.
 */
final class EvalEnv {

    private final Map<String, String> file;

    private EvalEnv(Map<String, String> file) {
        this.file = file;
    }

    static EvalEnv load(Path envFile) {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(envFile)) {
            try {
                for (String raw : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String line = raw.strip().replace("﻿", "");
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("export ")) line = line.substring(7).strip();
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String value = line.substring(eq + 1).strip();
                    if (value.length() >= 2
                            && (value.startsWith("\"") || value.startsWith("'"))
                            && value.charAt(value.length() - 1) == value.charAt(0)) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(line.substring(0, eq).strip(), value);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return new EvalEnv(values);
    }

    String get(String name, String fallback) {
        String shell = System.getenv(name);
        if (shell != null && !shell.isBlank()) return shell;
        String value = file.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    String require(String name) {
        String value = get(name, null);
        if (value == null) {
            throw new IllegalStateException(
                    name + " is not set (backend/.env.local or shell environment)");
        }
        return value;
    }

    Double number(String name) {
        String value = get(name, null);
        return value == null ? null : Double.valueOf(value);
    }

    /** .env.local 에 MODEL_{이름}_ID 로 적어 둔 모델 이름(소문자). 화면에 고를 목록으로 쓴다. 주소·키는 돌려주지 않는다. */
    List<String> modelNames() {
        return file.keySet().stream()
                .filter(key -> key.startsWith("MODEL_") && key.endsWith("_ID"))
                .map(key -> key.substring(6, key.length() - 3).toLowerCase(Locale.ROOT))
                .toList();
    }
}
