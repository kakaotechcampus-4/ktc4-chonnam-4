package com.neuringo.neuringobe.ai.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiFailureTypeTest {

    private static final Path CONTRACT_DOCUMENT = Path.of("docs/ai-provider-contract.md");
    private static final String FAILURE_TABLE_HEADING = "## 실패 분류";

    @ParameterizedTest
    @EnumSource(AiFailureType.class)
    void failureRetryableFollowsType(AiFailureType type) {
        AiFailure failure = new AiFailure(type, null);

        assertThat(failure.retryable()).isEqualTo(type.retryable());
    }

    @Test
    void contractDocumentFailureTableMatchesEnum() throws IOException {
        Map<String, Boolean> expected =
                Arrays.stream(AiFailureType.values())
                        .collect(
                                Collectors.toMap(
                                        AiFailureType::name,
                                        AiFailureType::retryable,
                                        (left, right) -> left,
                                        LinkedHashMap::new));

        assertThat(documentedFailureTypes()).containsExactlyEntriesOf(expected);
    }

    private Map<String, Boolean> documentedFailureTypes() throws IOException {
        List<String> lines = Files.readAllLines(CONTRACT_DOCUMENT);
        int headingIndex = lines.indexOf(FAILURE_TABLE_HEADING);
        assertThat(headingIndex).as("'%s' 섹션", FAILURE_TABLE_HEADING).isNotNegative();

        return lines.stream()
                .skip(headingIndex + 1)
                .dropWhile(line -> !line.startsWith("|"))
                .takeWhile(line -> line.startsWith("|"))
                .skip(2)
                .map(line -> Arrays.stream(line.split("\\|")).map(String::strip).toList())
                .collect(
                        Collectors.toMap(
                                cells -> cells.get(1).replace("`", ""),
                                cells -> parseRetryable(cells.get(3)),
                                (left, right) -> {
                                    throw new IllegalStateException("중복된 실패 유형 행");
                                },
                                LinkedHashMap::new));
    }

    private static boolean parseRetryable(String cell) {
        return switch (cell) {
            case "예" -> true;
            case "아니요" -> false;
            default -> throw new IllegalStateException("재시도 가능 값을 해석할 수 없음: " + cell);
        };
    }
}
