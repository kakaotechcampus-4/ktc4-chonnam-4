package com.neuringo.neuringobe.ai.eval.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 역할극 시뮬레이션 설정. 학습 목표(활동 하나)와 작은 목표, 아동 프로필을 담는다. 모두 합성 값이고 실제 아동 기록이 아니다.
 *
 * @param skills 작은 목표 키 → 강사 화면에 보일 이름
 */
record SimSpec(
        String version,
        Map<String, String> skills,
        List<LearningGoal> learningGoals,
        String startGoal,
        Session session,
        List<ChildProfile> children) {

    static final String DEFAULT_RESOURCE = "eval/roleplay-sim-v1.json";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    SimSpec {
        Objects.requireNonNull(version, "version must not be null");
        skills = Map.copyOf(skills);
        learningGoals = List.copyOf(learningGoals);
        children = List.copyOf(children);
        Objects.requireNonNull(session, "session must not be null");
        if (learningGoals.stream().noneMatch(goal -> goal.key().equals(startGoal))) {
            throw new IllegalArgumentException("unknown start goal " + startGoal);
        }
        for (LearningGoal goal : learningGoals) {
            if (!skills.keySet().containsAll(goal.micro())) {
                throw new IllegalArgumentException("unknown skill in " + goal.key());
            }
        }
    }

    static SimSpec load(String resource) {
        try (InputStream input = SimSpec.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalArgumentException("spec not found: " + resource);
            return JSON.readValue(input, SimSpec.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    LearningGoal goal(String key) {
        return learningGoals.stream()
                .filter(goal -> goal.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown goal " + key));
    }

    LearningGoal goal(UUID id) {
        return learningGoals.stream()
                .filter(goal -> goal.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown goal id " + id));
    }

    ChildProfile child(String key) {
        return children.stream()
                .filter(child -> child.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown child " + key));
    }

    /**
     * 활동 하나의 학습 목표. micro 는 작은 목표(skills 키) 순서다. criteria 는 작은 목표별 학습 증거 기준 설명(승인된 시나리오의
     * required_evidence 에 해당)이고, 없으면 기준 관찰을 만들지 않는다.
     */
    record LearningGoal(
            String key, String title, List<String> micro, Map<String, String> criteria) {
        LearningGoal {
            micro = List.copyOf(micro);
            criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
            if (micro.isEmpty()) throw new IllegalArgumentException("a goal needs micro goals");
        }

        /** micro 순서대로의 기준 설명. 기준이 하나라도 없으면 null. */
        List<String> criteriaInOrder() {
            if (!criteria.keySet().containsAll(micro)) return null;
            return micro.stream().map(criteria::get).toList();
        }

        UUID id() {
            return UUID.nameUUIDFromBytes(("goal:" + key).getBytes(StandardCharsets.UTF_8));
        }

        UUID microGoalId(String skill) {
            return UUID.nameUUIDFromBytes(
                    ("micro:" + key + ":" + skill).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 팀 develop 은 이 두 값을 운영 설정으로 받는다(ROLEPLAY_MAXIMUM_*). 기본값이 없어서 시뮬레이션이 정한다. */
    record Session(int maxTurns, int maxSupportTurnsPerGoal) {
        Session {
            if (maxTurns < 1 || maxSupportTurnsPerGoal < 1) {
                throw new IllegalArgumentException("turn limits must be positive");
            }
        }
    }

    /**
     * 합성 아동. ability 는 작은 목표별 0~1 실력(키 default 는 나머지 전부)이고, learnRate 만큼 연습하면 오른다. 나머지 비율은 한 번 답할 때
     * 그 일이 일어날 확률이다.
     */
    record ChildProfile(
            String key,
            String label,
            String purpose,
            String initialLevel,
            Map<String, Double> ability,
            double learnRate,
            double techFailRate,
            double offTopicRate,
            double refusalRate) {
        ChildProfile {
            ability = Map.copyOf(ability);
            if (!ability.containsKey("default")) {
                throw new IllegalArgumentException("ability needs a default");
            }
        }

        double abilityFor(String skill) {
            return ability.getOrDefault(skill, ability.get("default"));
        }
    }
}
