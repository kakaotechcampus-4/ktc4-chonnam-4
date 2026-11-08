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
 * 측정 실행기의 설정값. backend/.env.local 의 KEY=VALUE 를 우선하고, 없으면 셸 환경변수를 쓴다. 값은 출력하지 않는다.
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
                byte[] bytes = Files.readAllBytes(envFile);
                boolean utf16 =
                        bytes.length >= 2
                                && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                                        || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff));
                String text =
                        new String(bytes, utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
                for (String raw : text.split("\\R")) {
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
        String value = file.get(name);
        if (value != null && !value.isBlank()) return value;
        String shell = System.getenv(name);
        return shell == null || shell.isBlank() ? fallback : shell;
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
