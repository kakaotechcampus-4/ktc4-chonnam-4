package com.neuringo.neuringobe.report;

import com.neuringo.neuringobe.report.application.RoleplayAnswerMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 역할극을 마친 활동 하나를 DB 에 직접 만든다. 승인 시나리오(L2, 작은 목표 3개)와 완료 세션, 답 기록 턴·판정·관찰을 넣는다. 팀 역할극 판단(LLM)을 거치지
 * 않고 리포트 쪽 입력만 맞춘다.
 *
 * <p>목표별 결과는 이렇게 정해 둔다(시작 지원 S1).
 *
 * <ul>
 *   <li>감정 이름 말하기: S1 에서 바로 달성. 처음 지원으로 도달
 *   <li>감정의 이유 말하기: S1 일부 이해, S2 에서 달성. 지원을 더 받고 도달
 *   <li>위로하는 말 하기: S1·S2 도움 요청, 답을 들은 뒤(S3) 따라 말함
 * </ul>
 *
 * 점수율은 (6 + 3 + 0) / 18 = 50% 라서 다음 난이도는 L2 유지(SCORE_IN_KEEP_RANGE)이고, 추천은 보완·반복·적용 3개다.
 */
public final class RoleplayReportSeed {
    public static final String EMOTION = "감정 이름 말하기";
    public static final String CAUSE = "감정의 이유 말하기";
    public static final String COMFORT = "위로하는 말 하기";

    private final JdbcTemplate jdbc;

    public RoleplayReportSeed(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Seeded completedRoleplay(UUID instructorId, UUID classId, UUID childId, Instant now) {
        return completedRoleplay(instructorId, classId, childId, now, "ROLEPLAY");
    }

    /**
     * @param activityType ROLEPLAY 또는 FIRST_COMBINED. 최초 결합이면 퀴즈 결과(3문항 중 첫 정답 2개, 힌트 뒤 해결 1개,
     *     L2·S1)도 넣는다
     */
    public Seeded completedRoleplay(
            UUID instructorId, UUID classId, UUID childId, Instant now, String activityType) {
        Timestamp at = Timestamp.from(now);
        UUID goal = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,category,content_hash,created_at) values(?,?,?,?,?,?,?)",
                goal,
                childId,
                instructorId,
                "넘어진 친구의 속상한 마음 알아보기",
                "EMPATHY",
                hash,
                at);
        UUID scenario = UUID.randomUUID();
        jdbc.update(
                """
                insert into scenario(scenario_id,status,scenario_level,goal_content_hash,title,scenario_facts,origin,created_at,class_id,scenario_version)
                values(?,'DRAFT','L2',?,'놀이터에서 넘어진 친구','["친구가 넘어졌다"]'::jsonb,'PREPARED',?,?,1)
                """,
                scenario,
                hash,
                at,
                classId);
        UUID emotion = UUID.randomUUID(), cause = UUID.randomUUID(), comfort = UUID.randomUUID();
        microGoal(scenario, emotion, 1, EMOTION, "속상한 감정");
        microGoal(scenario, cause, 2, CAUSE, "넘어져서 아픈 이유");
        microGoal(scenario, comfort, 3, COMFORT, "괜찮냐고 묻는 말");
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenario);

        UUID activity = UUID.randomUUID();
        jdbc.update(
                """
                insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,initial_support_level,status,assigned_at,started_at,completed_at,reward_issued_at)
                values(?,?,?,?,?,'S1','COMPLETED',?,?,?,?)
                """,
                activity,
                childId,
                goal,
                scenario,
                activityType,
                at,
                at,
                at,
                at);
        if ("FIRST_COMBINED".equals(activityType)) {
            jdbc.update(
                    """
                    insert into quiz_result(activity_id,valid_question_count,correct_question_count,total_hint_count,
                        resolved_after_hint_count,scenario_level,initial_support_level,difficulty_fallback_applied,
                        initial_difficulty_used,policy_version,snapshotted_at)
                    values(?,3,2,1,1,'L2','S1',false,true,'INITIAL_QUIZ_V2',?)
                    """,
                    activity,
                    at);
        }
        UUID record = UUID.randomUUID();
        jdbc.update(
                "insert into learning_record(record_id,activity_id,created_at) values(?,?,?)",
                record,
                activity,
                at);
        UUID session = UUID.randomUUID();
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'COMPLETED',?)",
                session,
                activity,
                childId,
                scenario,
                at);

        List<UUID> turns = new ArrayList<>();
        turns.add(
                turn(session, scenario, 1, emotion, "S1", false, "GOAL_ACHIEVED", "ACHIEVED", at));
        observe(session, turns.getLast(), emotion, "속상한 감정", "EXPLICIT");
        turns.add(
                turn(
                        session,
                        scenario,
                        2,
                        cause,
                        "S1",
                        false,
                        "PARTIAL_UNDERSTANDING",
                        "IN_PROGRESS",
                        at));
        observe(session, turns.getLast(), cause, "넘어져서 아픈 이유", "IMPLICIT");
        turns.add(
                turn(session, scenario, 3, cause, "S2", false, "GOAL_ACHIEVED", "IN_PROGRESS", at));
        observe(session, turns.getLast(), cause, "넘어져서 아픈 이유", "EXPLICIT");
        turns.add(
                turn(session, scenario, 4, comfort, "S1", false, "HELP_NEEDED", "IN_PROGRESS", at));
        turns.add(
                turn(session, scenario, 5, comfort, "S2", false, "HELP_NEEDED", "IN_PROGRESS", at));
        turns.add(turn(session, scenario, 6, comfort, "S3", true, "GOAL_ACHIEVED", "ACHIEVED", at));
        observe(session, turns.getLast(), comfort, "괜찮냐고 묻는 말", "EXPLICIT");
        jdbc.update(
                "update roleplay_session set last_turn_number=?, last_turn_id=? where session_id=?",
                turns.size(),
                turns.getLast(),
                session);
        return new Seeded(goal, scenario, activity, record, session);
    }

    /**
     * 학급·목표 내용(content_hash)·레벨이 맞는 승인 시나리오를 하나 더 넣는다. 시작 질문은 S0~S2 모두 두고, 시작 초점은 첫 작은 목표다. 역할극 단독
     * 활동이 시작할 때 이 시나리오가 붙는다.
     */
    public UUID approvedScenario(UUID classId, String goalContentHash, String level) {
        UUID scenario = UUID.randomUUID();
        jdbc.update(
                """
                insert into scenario(scenario_id,status,scenario_level,goal_content_hash,title,scenario_facts,opening_prompts,origin,created_at,class_id,scenario_version)
                values(?,'DRAFT',?,?,'교실에서 넘어진 친구','["친구가 넘어졌다"]'::jsonb,
                    '{"S0":"친구는 지금 어떤 마음일까?","S1":"친구가 무릎을 잡고 있어. 어떤 마음일까?","S2":"친구는 속상할까, 신날까?"}'::jsonb,
                    'PREPARED',now(),?,1)
                """,
                scenario,
                level,
                goalContentHash,
                classId);
        UUID first = UUID.randomUUID();
        microGoal(scenario, first, 1, EMOTION, "속상한 감정");
        microGoal(scenario, UUID.randomUUID(), 2, CAUSE, "넘어져서 아픈 이유");
        for (String support : List.of("S0", "S1", "S2")) {
            jdbc.update(
                    "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,?,?)",
                    scenario,
                    support,
                    first);
        }
        jdbc.update("update scenario set status='APPROVED' where scenario_id=?", scenario);
        return scenario;
    }

    private void microGoal(UUID scenario, UUID id, int order, String description, String evidence) {
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,?,?,?::jsonb)",
                id,
                scenario,
                order,
                description,
                "[\"" + evidence + "\"]");
    }

    private UUID turn(
            UUID session,
            UUID scenario,
            int number,
            UUID goal,
            String support,
            boolean revealed,
            String learningState,
            String decision,
            Timestamp at) {
        UUID turn = UUID.randomUUID();
        jdbc.update(
                """
                insert into roleplay_turn(turn_id,session_id,request_id,idempotency_key,input_fingerprint,turn_number,candidate_id,
                    micro_goal_id,support_level,response_text,checkpoint_version,committed_at,
                    input_micro_goal_id,input_support_level,input_support_turn,input_prompt_count,input_answer_revealed)
                values(?,?,?,?,?,?,?,?,?,'합성 응답',?,?,?,?,1,1,?)
                """,
                turn,
                session,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                number,
                UUID.randomUUID(),
                goal,
                support,
                number,
                at,
                goal,
                support,
                revealed);
        jdbc.update(
                """
                insert into roleplay_goal_assessment(session_id,turn_id,checkpoint_version,scenario_id,scenario_version,approval_reference,
                    analysis_contract_version,evaluation_contract_version,decision,input_micro_goal_id,input_learning_state)
                values(?,?,?,?,1,'synthetic-approval','analysis-result/v2','evaluation-result/v2',?,?,?)
                """,
                session,
                turn,
                number,
                scenario,
                decision,
                goal,
                learningState);
        return turn;
    }

    private void observe(UUID session, UUID turn, UUID goal, String evidence, String status) {
        jdbc.update(
                "insert into roleplay_goal_observation(session_id,turn_id,micro_goal_id,criterion_reference,observation_status) values(?,?,?,?,?)",
                session,
                turn,
                goal,
                RoleplayAnswerMapper.criterionReference(goal, evidence),
                status);
    }

    public record Seeded(UUID goal, UUID scenario, UUID activity, UUID record, UUID session) {}
}
