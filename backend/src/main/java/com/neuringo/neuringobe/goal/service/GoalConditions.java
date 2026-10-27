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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 목표 조건의 정리·모순 검사·해시 규칙. 스프링과 DB 없이 같은 입력이면 같은 결과를 낸다.
 *
 * <ul>
 *   <li>정리: 앞뒤 공백을 지우고 안쪽 여러 칸 공백을 한 칸으로 줄인다. 전각 공백·줄 바꿈 없는 공백 같은 유니코드 공백도 공백으로 본다. 목록은 같은 값을 한 번만
 *       남기고 입력 순서를 지킨다.
 *   <li>등장인물: {@code {id, role}} 로 저장한다. id 가 비면 {@code OTHER} 이고, 같은 id 가 둘 이상이면 {@code FRIEND_2}
 *       처럼 번호를 붙인다. 시나리오 생성은 입력 id 를 그대로 쓰는데, 생성 결과 검사가 id 중복을 실패로 보기 때문이다. 번호는 role 글자 순서로 매겨 칩
 *       순서와 상관없이 같은 id 가 된다.
 *   <li>모순: 피할 표현이 필수 요소나 등장인물 role 안에 들어 있으면 그 피할 표현을 가리켜 거절한다. 시나리오 생성 결과 검사와 같이 띄어쓰기와 대소문자를
 *       무시한다. 그 검사는 생성 결과 전체(등장인물 role 포함)에서 피할 표현을 찾으므로, 이런 조건은 생성이 계속 실패하게 만든다. 시나리오의 아이 자리인
 *       {@code LEARNER} id 도 거절한다.
 *   <li>해시: 정리한 값으로 계산하되 목록은 정렬해 넣는다. 칩 순서만 다른 목표는 같은 목표다.
 * </ul>
 *
 * <p>검사는 저장할 값과 같은 정리를 거친 값으로 한다. 정리 전 값으로 검사하면 정리에서만 지워지는 공백 때문에, 검사는 통과하고 저장된 조건은 모순일 수 있다. 밖에서는
 * {@link #prepare} 하나로 검사와 정리를 함께 받는다.
 */
final class GoalConditions {

    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper HASH_JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final String OTHER = "OTHER";
    private static final String LEARNER = "LEARNER";
    private static final Comparator<Map<String, Object>> BY_ROLE_THEN_ID =
            Comparator.comparing((Map<String, Object> character) -> (String) character.get("role"))
                    .thenComparing(character -> (String) character.get("id"));

    private GoalConditions() {}

    /** 정리한 목표 조건. 등장인물은 저장 모양({@code id}, {@code role})이다. */
    record Normalized(
            String title,
            String situationType,
            String category,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}

    /** 검사 결과. 걸린 곳이 없을 때만 {@code goal} 이 있다. */
    record Prepared(List<FieldError> errors, Normalized goal) {}

    /** 검사하고, 통과하면 저장할 모양으로 정리해 함께 돌려준다. */
    static Prepared prepare(
            String title,
            String situationType,
            String category,
            List<GoalCharacter> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions,
            String fieldPrefix) {
        List<FieldError> errors =
                violations(characters, requiredElements, forbiddenExpressions, fieldPrefix);
        if (!errors.isEmpty()) {
            return new Prepared(errors, null);
        }
        return new Prepared(
                List.of(),
                normalize(
                        title,
                        situationType,
                        category,
                        characters,
                        requiredElements,
                        forbiddenExpressions));
    }

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
            GoalCharacter character = characters.get(i);
            if (character == null) {
                errors.add(
                        new FieldError(fieldPrefix + "characters[" + i + "]", "비어 있는 등장인물이 있습니다."));
            } else if (LEARNER.equals(character.id())) {
                errors.add(
                        new FieldError(
                                fieldPrefix + "characters[" + i + "].id",
                                "LEARNER 는 역할극의 아이 자리라 등장인물로 둘 수 없습니다."));
            }
        }
        addBlankItems(errors, requiredElements, fieldPrefix + "requiredElements");
        addBlankItems(errors, forbiddenExpressions, fieldPrefix + "forbiddenExpressions");
        if (!errors.isEmpty()) {
            return errors;
        }

        List<String> targets = new ArrayList<>();
        requiredElements.forEach(element -> targets.add(compact(element)));
        characters.forEach(character -> targets.add(compact(character.role())));
        for (int i = 0; i < forbiddenExpressions.size(); i++) {
            String forbidden = compact(forbiddenExpressions.get(i));
            if (targets.stream().anyMatch(target -> target.contains(forbidden))) {
                errors.add(
                        new FieldError(
                                fieldPrefix + "forbiddenExpressions[" + i + "]",
                                "필수 요소나 등장인물에 들어 있는 표현은 피할 표현으로 둘 수 없습니다."));
            }
        }
        return errors;
    }

    /** {@link #violations} 가 비어 있을 때만 부른다. 밖에서는 {@link #prepare} 를 쓴다. */
    static Normalized normalize(
            String title,
            String situationType,
            String category,
            List<GoalCharacter> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {
        List<Map<String, Object>> storedCharacters =
                numberRepeatedIds(
                        characters.stream().map(GoalConditions::toStored).distinct().toList());
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
        characters.sort(BY_ROLE_THEN_ID);
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
            if (value == null || text(value).isEmpty()) {
                errors.add(new FieldError(field + "[" + i + "]", "비어 있는 항목이 있습니다."));
            }
        }
    }

    private static Map<String, Object> toStored(GoalCharacter character) {
        Map<String, Object> stored = new LinkedHashMap<>();
        String id = blankToNull(text(character.id()));
        stored.put("id", id == null ? OTHER : id);
        stored.put("role", text(character.role()));
        return stored;
    }

    /**
     * 같은 id 를 쓰는 등장인물 중 role 글자 순서로 첫 인물만 id 를 그대로 두고, 나머지는 쓰이지 않은 {@code _2}, {@code _3} 을 붙인다. 목록
     * 순서는 그대로다.
     */
    private static List<Map<String, Object>> numberRepeatedIds(
            List<Map<String, Object>> characters) {
        Map<String, List<Map<String, Object>>> byId = new LinkedHashMap<>();
        characters.forEach(
                character ->
                        byId.computeIfAbsent((String) character.get("id"), id -> new ArrayList<>())
                                .add(character));
        Set<String> taken = new HashSet<>(byId.keySet());
        Map<Map<String, Object>, String> renamed = new IdentityHashMap<>();
        byId.forEach(
                (id, sameId) -> {
                    List<Map<String, Object>> byRole = new ArrayList<>(sameId);
                    byRole.sort(BY_ROLE_THEN_ID);
                    int number = 2;
                    for (Map<String, Object> character : byRole.subList(1, byRole.size())) {
                        while (taken.contains(id + "_" + number)) {
                            number++;
                        }
                        String numbered = id + "_" + number;
                        taken.add(numbered);
                        renamed.put(character, numbered);
                    }
                });
        return characters.stream()
                .map(
                        character -> {
                            String numbered = renamed.get(character);
                            if (numbered == null) {
                                return character;
                            }
                            Map<String, Object> copy = new LinkedHashMap<>(character);
                            copy.put("id", numbered);
                            return copy;
                        })
                .toList();
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
        return value == null ? null : SPACES.matcher(value).replaceAll(" ").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 시나리오 생성 결과의 피할 표현 검사와 같은 비교 기준(공백 제거, 소문자). 저장할 값과 같게 정리한 뒤 비교한다. */
    private static String compact(String value) {
        return text(Objects.requireNonNull(value)).replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private record HashInput(
            String title,
            String situationType,
            String category,
            List<Map<String, Object>> characters,
            List<String> requiredElements,
            List<String> forbiddenExpressions) {}
}
