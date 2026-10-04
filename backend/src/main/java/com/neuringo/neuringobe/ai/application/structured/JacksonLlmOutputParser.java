package com.neuringo.neuringobe.ai.application.structured;

import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;

public final class JacksonLlmOutputParser<T> implements LlmOutputParser<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> outputType;

    public JacksonLlmOutputParser(ObjectMapper objectMapper, Class<T> outputType) {
        // 받은 매퍼의 설정(모듈 등)은 그대로 두고 값 변환만 끈다. 기본 설정은 문자열 "true"·숫자 1 을 참으로, 숫자 0 을 첫 enum(PASS)으로,
        // 0.9 를 0 으로 바꿔 읽어서 형식이 틀린 평가 결과도 전달 게이트를 통과했다(#22 의 2번). 형식이 틀리면 INVALID_OUTPUT_FORMAT 이다.
        this.objectMapper =
                Objects.requireNonNull(objectMapper)
                        .rebuild()
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                        .build();
        this.outputType = Objects.requireNonNull(outputType);
    }

    @Override
    public T parse(String content) {
        T output;
        try {
            output = objectMapper.readValue(content, outputType);
        } catch (RuntimeException exception) {
            throw new InvalidLlmOutputException(
                    "LLM output does not satisfy the required structure", exception);
        }
        // completion 이 JSON null 이면 readValue 가 null 을 돌려주고, 그대로 넘기면 부르는 쪽에서 예외(NPE)가 난다.
        if (output == null) {
            throw new InvalidLlmOutputException("LLM output is null", null);
        }
        return output;
    }
}
