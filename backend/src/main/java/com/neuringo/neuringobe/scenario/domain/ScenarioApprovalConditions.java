package com.neuringo.neuringobe.scenario.domain;

import java.util.List;
import java.util.regex.Pattern;

/** 시나리오 조건의 정규화 값이다. 승인 기록에는 최종 확인한 값만 사용한다. */
public record ScenarioApprovalConditions(
        String goalTitle,
        String scenarioLevel,
        String category,
        String situationType,
        List<String> characterRoles,
        List<String> requiredActions) {

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public ScenarioApprovalConditions {
        goalTitle = requiredText(goalTitle);
        if (scenarioLevel == null || !List.of("L1", "L2", "L3").contains(scenarioLevel)) {
            throw new IllegalArgumentException("유효한 시나리오 난이도가 필요합니다.");
        }
        category = optionalText(category);
        situationType = optionalText(situationType);
        // 같은 역할이 여러 명이면 인원수도 조건이므로 중복을 보존한다.
        characterRoles = normalizedItems(characterRoles, false);
        requiredActions = normalizedItems(requiredActions, true);
    }

    private static String requiredText(String value) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException("확인한 조건은 빈 문자열일 수 없습니다.");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> normalizedItems(List<String> values, boolean distinct) {
        if (values == null) {
            return null;
        }
        var stream = values.stream().map(ScenarioApprovalConditions::requiredText).sorted();
        return (distinct ? stream.distinct() : stream).toList();
    }
}
