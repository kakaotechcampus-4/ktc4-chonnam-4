package com.neuringo.neuringobe.ai.application.prompt;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 리포트 프롬프트 입력. 근거마다 ID 를 붙여서, 초안의 문장이 어느 근거에 기대는지 검사할 수 있게 한다. 아동 이름·표준 발화 원문·추적 ID 는 넣지 않는다.
 *
 * @param metrics 0~1 로 정규화한 지표. 이름 순서를 지켜 프롬프트에 넣는다. 잴 수 없는 지표는 빠진다.
 * @param difficulty 이 활동의 난이도 근거. 없으면 null.
 */
public record LearningReportInput(
        List<Evidence> evidence, Map<String, Double> metrics, Difficulty difficulty) {

    public enum EvidenceKind {
        QUIZ_RESULT,
        GOAL_RESULT,
        OBSERVATION,
        EFFECTIVE_SUPPORT,
        /** 기술 오류. 아동의 어려움 근거로 쓸 수 없다. */
        TECHNICAL
    }

    /**
     * 근거가 속한 목표의 결과. 초안이 근거를 맞는 칸에 쓰는지(어려운 지점·효과적인 지원) 코드로 검사하려고 붙인다.
     *
     * <ul>
     *   <li>NO_SUPPORT_NEEDED: S0(열린 질문)에서 시작해 지원을 더 받지 않고 도달
     *   <li>REACHED_AT_START: S1·S2 로 시작해 시작 단계에서 도달. 시작 지원은 효과가 있었다
     *   <li>EXTRA_SUPPORT: 시작보다 지원을 더 받고 S0~S2 에서 도달
     *   <li>ANSWER_GIVEN: S3(답 알려 주기)에서 도달
     * </ul>
     */
    public enum GoalStatus {
        NO_SUPPORT_NEEDED,
        REACHED_AT_START,
        EXTRA_SUPPORT,
        ANSWER_GIVEN,
        NOT_REACHED,
        EXCLUDED;

        /** 어려운 지점의 근거가 될 수 없는 결과. */
        public boolean reachedWithoutDifficulty() {
            return this == NO_SUPPORT_NEEDED || this == REACHED_AT_START;
        }
    }

    /**
     * @param goalStatus 목표에 속한 근거만 있다. 퀴즈·기술 오류 코드 근거는 null
     */
    public record Evidence(
            String id, EvidenceKind kind, String goalTitle, String text, GoalStatus goalStatus) {

        public Evidence {
            requireText(id, "id");
            Objects.requireNonNull(kind, "kind must not be null");
            requireText(text, "text");
        }

        public Evidence(String id, EvidenceKind kind, String goalTitle, String text) {
            this(id, kind, goalTitle, text, null);
        }
    }

    public record Difficulty(
            String decisionType, String scenarioLevel, String reasonCode, String policyVersion) {

        public Difficulty {
            requireText(decisionType, "decisionType");
            requireText(scenarioLevel, "scenarioLevel");
            requireText(reasonCode, "reasonCode");
            requireText(policyVersion, "policyVersion");
        }
    }

    public LearningReportInput {
        Objects.requireNonNull(evidence, "evidence must not be null");
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) throw new IllegalArgumentException("evidence must not be empty");
        Set<String> ids = new HashSet<>();
        for (Evidence item : evidence) {
            if (!ids.add(item.id()))
                throw new IllegalArgumentException("evidence ids must be unique");
        }
        metrics =
                metrics == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
    }

    public Set<String> evidenceIds() {
        return evidence.stream().map(Evidence::id).collect(Collectors.toUnmodifiableSet());
    }

    /** ID 로 근거를 찾는다. 없으면 null. */
    public Evidence find(String id) {
        return evidence.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    public Set<String> evidenceIdsOf(EvidenceKind kind) {
        return evidence.stream()
                .filter(item -> item.kind() == kind)
                .map(Evidence::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
