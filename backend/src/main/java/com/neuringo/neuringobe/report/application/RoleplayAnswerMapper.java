package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.domain.roleplay.CriterionObservation;
import com.neuringo.neuringobe.report.domain.roleplay.LearningState;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayMicroGoal;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayTurnRecord;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 답 기록 행을 리포트 도메인 입력(RoleplayMicroGoal·RoleplayTurnRecord)으로 바꾼다.
 *
 * <p>관찰의 기준은 DB 에 해시(criterion_reference)로만 남아 있다. 역할극 문맥을 만드는 JdbcRoleplayContextSource 와 같은 방식(
 * {@code "evidence/v1:" + sha256(microGoalId + "\n" + 기준 문장)})으로 승인 시나리오의 기준 문장마다 해시를 다시 계산해 문장을
 * 찾는다. 맞는 기준이 없는 관찰은 버리고 개수만 센다.
 *
 * <p>한 턴의 관찰은 그 턴에 아동이 답한 작은 목표(input_micro_goal_id)의 것만 쓴다. 응답 거부(response_act)는 DB 에 남지 않아서 거부로
 * 빼는 목표는 없다.
 */
public final class RoleplayAnswerMapper {

    static final String CRITERION_PREFIX = "evidence/v1:";

    public MappedRoleplay map(RoleplayAnswerRows rows) {
        List<RoleplayMicroGoal> goals = new ArrayList<>();
        Map<String, String> criterionByReference = new HashMap<>();
        Map<UUID, List<String>> criteriaByGoal = new LinkedHashMap<>();
        for (RoleplayAnswerRows.MicroGoalRow row : rows.goals()) {
            goals.add(new RoleplayMicroGoal(row.microGoalId(), row.description()));
            criteriaByGoal.put(row.microGoalId(), row.requiredEvidence());
            for (String evidence : row.requiredEvidence()) {
                criterionByReference.put(criterionReference(row.microGoalId(), evidence), evidence);
            }
        }

        Map<UUID, List<RoleplayAnswerRows.ObservationRow>> observationsByTurn = new HashMap<>();
        for (RoleplayAnswerRows.ObservationRow observation : rows.observations()) {
            observationsByTurn
                    .computeIfAbsent(observation.turnId(), id -> new ArrayList<>())
                    .add(observation);
        }

        int unmatched = 0;
        List<RoleplayTurnRecord> turns = new ArrayList<>();
        // 기준마다 마지막으로 관찰한 상태. 강사 화면 역할극 탭의 기준별 표시에 쓴다.
        Map<UUID, Map<String, CriterionObservation.Status>> latest = new HashMap<>();
        for (RoleplayAnswerRows.TurnRow turn : rows.turns()) {
            if (!criteriaByGoal.containsKey(turn.inputMicroGoalId())) {
                continue;
            }
            List<CriterionObservation> observations = new ArrayList<>();
            for (RoleplayAnswerRows.ObservationRow observation :
                    observationsByTurn.getOrDefault(turn.turnId(), List.of())) {
                if (!turn.inputMicroGoalId().equals(observation.microGoalId())) {
                    continue;
                }
                String criterion = criterionByReference.get(observation.criterionReference());
                if (criterion == null) {
                    unmatched++;
                    continue;
                }
                var status = CriterionObservation.Status.valueOf(observation.status());
                observations.add(new CriterionObservation(criterion, status));
                latest.computeIfAbsent(turn.inputMicroGoalId(), id -> new LinkedHashMap<>())
                        .put(criterion, status);
            }
            // 답을 알려 준 뒤의 답은 지원 단계 기록과 상관없이 S3 에서 한 답으로 본다(팀 진행 규칙의 ANSWER_REVEALED 와 같은 기준).
            SupportLevel support =
                    turn.answerRevealed()
                            ? SupportLevel.S3
                            : SupportLevel.valueOf(turn.inputSupportLevel());
            turns.add(
                    new RoleplayTurnRecord(
                            turn.inputMicroGoalId(),
                            turn.turnNumber(),
                            support,
                            LearningState.valueOf(turn.learningState()),
                            false,
                            observations));
        }

        Map<UUID, List<CriterionView>> criteria = new LinkedHashMap<>();
        criteriaByGoal.forEach(
                (goalId, evidence) -> {
                    var seen = latest.getOrDefault(goalId, Map.of());
                    criteria.put(
                            goalId,
                            evidence.stream()
                                    .map(text -> new CriterionView(text, seen.get(text)))
                                    .toList());
                });

        return new MappedRoleplay(
                ScenarioLevel.valueOf(rows.scenarioLevel()),
                startSupport(rows, turns),
                goals,
                turns,
                criteria,
                unmatched);
    }

    /**
     * 활동에 적용한 시작 지원을 쓴다. 옛 활동처럼 값이 없으면 첫 답의 지원 단계, 그마저 없으면 시나리오 레벨의 시작 지원으로 본다. S3 는 시작 지원이 될 수 없다.
     */
    private static SupportLevel startSupport(
            RoleplayAnswerRows rows, List<RoleplayTurnRecord> turns) {
        String stored = rows.startSupportLevel();
        if (stored != null && !stored.isBlank() && !"S3".equals(stored)) {
            return SupportLevel.valueOf(stored);
        }
        if (!turns.isEmpty()) {
            return turns.getFirst().inputSupport();
        }
        return ScenarioLevel.valueOf(rows.scenarioLevel()).startSupport();
    }

    public static String criterionReference(UUID microGoalId, String evidence) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (microGoalId + "\n" + evidence)
                                            .getBytes(StandardCharsets.UTF_8));
            return CRITERION_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /**
     * @param unmatchedObservations 승인 시나리오의 기준과 해시가 맞지 않아 버린 관찰 수. 0 이 아니면 시나리오나 해시 규칙이 바뀐 것이다
     */
    public record MappedRoleplay(
            ScenarioLevel level,
            SupportLevel startSupport,
            List<RoleplayMicroGoal> goals,
            List<RoleplayTurnRecord> turns,
            Map<UUID, List<CriterionView>> criteria,
            int unmatchedObservations) {}

    /** 기준 문장과 마지막 관찰 상태. 한 번도 관찰하지 않았으면 status 가 null 이다. */
    public record CriterionView(String description, CriterionObservation.Status status) {}
}
