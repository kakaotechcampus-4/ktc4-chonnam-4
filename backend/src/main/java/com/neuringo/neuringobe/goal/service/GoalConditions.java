package com.neuringo.neuringobe.goal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.neuringo.neuringobe.common.ApiErrorResponse.FieldError;
import com.neuringo.neuringobe.goal.dto.GoalCharacter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 목표 조건의 정리·모순 검사·해시 규칙. 스프링과 DB 없이 같은 입력이면 같은 결과를 낸다.
 *
 * <ul>
 *   <li>정리: 앞뒤 공백을 지우고 안쪽 여러 칸 공백을 한 칸으로 줄인다. 목록은 같은 값을 한 번만 남기고 입력 순서를 지킨다.
 *   <li>모순: 피할 표현이 필수 요소 안에 들어 있으면 그 피할 표현을 가리켜 거절한다. 시나리오 생성 결과 검사와 같이 띄어쓰기와 대소문자를 무시한다. 그 검사는 생성
 *       결과 전체에서 피할 표현을 찾으므로, 필수 요소에 들어간 피할 표현은 생성이 계속 실패하게 만든다.
 *   <li>해시: 정리한 값으로 계산하되 목록은 정렬해 넣는다. 칩 순서만 다른 목표는 같은 목표다.
 * </ul>
 */
final class GoalConditions {

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final ObjectMapper HASH_JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Comparator<Map<String, Object>> BY_NAME_THEN_RELATION =
            Comparator.comparing((Map<String, Object> character) -> (String) character.get("name"))
                    .thenComparing(
                            character -> (String) character.get("relation"),
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private GoalConditions() {}

    /** 정리한 목표 조건. 등장인물은 저장 모양({@code name}, 있으면 {@code relation})이다. */
    record Normalized(
            String title,
            String situationType,
            String category,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}

    /**
     * 정리하고 검사한다. 걸린 곳이 있으면 {@code fieldPrefix} 를 붙인 필드 경로로 모두 돌려주고, 없으면 빈 목록이다. 목록 안 빈 값·null 과 모순은
     * 요청에서의 위치(정리 전 인덱스)로 가리켜, 화면이 보낸 칩을 그대로 찾을 수 있게 한다.
     */
    static List<FieldError> violations(
            List<GoalCharacter> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions,
            String fieldPrefix) {
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i) == null) {
                errors.add(
                        new FieldError(fieldPrefix + "characters[" + i + "]", "비어 있는 등장인물이 있습니다."));
            }
        }
        addBlankItems(errors, requiredElements, fieldPrefix + "requiredElements");
        addBlankItems(errors, forbiddenExpressions, fieldPrefix + "forbiddenExpressions");
        if (!errors.isEmpty()) {
            return errors;
        }

        List<String> required = requiredElements.stream().map(GoalConditions::compact).toList();
        for (int i = 0; i < forbiddenExpressions.size(); i++) {
            String forbidden = compact(forbiddenExpressions.get(i));
            if (required.stream().anyMatch(element -> element.contains(forbidden))) {
                errors.add(
                        new FieldError(
                                fieldPrefix + "forbiddenExpressions[" + i + "]",
                                "필수 요소에 들어 있는 표현은 피할 표현으로 둘 수 없습니다."));
            }
        }
        return errors;
    }

    /** {@link #violations} 가 비어 있을 때만 부른다. */
    static Normalized normalize(
            String title,
            String situationType,
            String category,
            List<GoalCharacter> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {
        List<Map<String, Object>> storedCharacters =
                characters.stream().map(GoalConditions::toStored).distinct().toList();
        return new Normalized(
                text(title),
                blankToNull(text(situationType)),
                blankToNull(text(category)),
                storedCharacters,
                distinctTexts(requiredElements),
                distinctTexts(forbiddenExpressions));
    }

    static String contentHash(Normalized goal) {
        List<Map<String, Object>> characters = new ArrayList<>(goal.characters());
        characters.sort(BY_NAME_THEN_RELATION);
        HashInput input =
                new HashInput(
                        goal.title(),
                        goal.situationType(),
                        goal.category(),
                        characters,
                        goal.requiredElements().stream().sorted().toList(),
                        goal.forbiddenExpressions().stream().sorted().toList());
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(HASH_JSON.writeValueAsBytes(input));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("목표 해시 계산에 실패했습니다.", ex);
        }
    }

    private static void addBlankItems(List<FieldError> errors, List<String> values, String field) {
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null || value.isBlank()) {
                errors.add(new FieldError(field + "[" + i + "]", "비어 있는 항목이 있습니다."));
            }
        }
    }

    private static Map<String, Object> toStored(GoalCharacter character) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("name", text(character.name()));
        String relation = blankToNull(text(character.relation()));
        if (relation != null) {
            stored.put("relation", relation);
        }
        return stored;
    }

    private static List<String> distinctTexts(List<String> values) {
        return List.copyOf(
                values.stream()
                        .map(GoalConditions::text)
                        .collect(
                                LinkedHashSet<String>::new,
                                LinkedHashSet::add,
                                LinkedHashSet::addAll));
    }

    private static String text(String value) {
        return value == null ? null : SPACES.matcher(value.strip()).replaceAll(" ");
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 시나리오 생성 결과의 피할 표현 검사와 같은 비교 기준(공백 제거, 소문자). */
    private static String compact(String value) {
        return SPACES.matcher(Objects.requireNonNull(value))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    private record HashInput(
            String title,
            String situationType,
            String category,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}
}
