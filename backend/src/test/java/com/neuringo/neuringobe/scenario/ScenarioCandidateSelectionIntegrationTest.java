package com.neuringo.neuringobe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.quiz.service.InitialDifficultyQueryService;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateSelection;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateContentRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateRepository;
import com.neuringo.neuringobe.scenario.service.InitialScenarioPreparationService;
import com.neuringo.neuringobe.scenario.service.ScenarioApprovalRegistry;
import com.neuringo.neuringobe.scenario.service.ScenarioCandidateQueryService;
import com.neuringo.neuringobe.scenario.support.ScenarioPoolTestIsolation;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@org.junit.jupiter.api.extension.ExtendWith(ScenarioPoolTestIsolation.class)
@ExtendWith(OutputCaptureExtension.class)
class ScenarioCandidateSelectionIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ScenarioApprovalRegistry approvals;
    @Autowired private ScenarioCandidateRepository candidates;
    @Autowired private ScenarioCandidateContentRepository contents;
    @Autowired private LearningGoalService goals;
    @Autowired private ChildRepository children;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JsonMapper json;

    @Test
    void matchesNormalizedConditionsWithoutFullHashAndPreservesRequiredActionSubset(
            CapturedOutput output) {
        var ctx = context("CLASS", "SCHOOL", List.of("친구", "아이"), List.of("기다리기"), List.of());
        var confirmed =
                conditions(
                        "  차례를  기다려요  ",
                        "L2",
                        "CLASS",
                        "SCHOOL",
                        List.of("아이", "친구"),
                        List.of("기다리기", "말하기"));
        UUID scenario = candidate(ctx, confirmed, id -> {});

        var result = select(ctx);

        assertThat(result.candidates()).extracting(c -> c.scenarioId()).containsExactly(scenario);
        assertThat(result.poolScenarioCount()).isEqualTo(1);
        assertThat(result.exclusionCounts()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT goal_content_hash FROM scenario WHERE scenario_id = ?",
                                String.class,
                                scenario))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM activity WHERE child_id = ?",
                                Integer.class,
                                ctx.child()))
                .isZero();
        assertThat(output)
                .contains(
                        "event=scenario_reuse_selection", "queryMs=", "contentCheckMs=", "totalMs=")
                .doesNotContain("차례를 기다려요", "검사 아동", "@example.com");
    }

    @Test
    void sharesConfirmedConditionsAndStillExcludesUnverifiedLegacyApprovals() {
        var ctx = context(null, null, List.of(), List.of(), List.of());
        UUID own = candidate(ctx, conditions("차례를 기다려요", "L2", null, null, null, null), id -> {});
        var other = context(null, null, List.of(), List.of(), List.of());
        UUID shared =
                candidate(other, conditions("차례를 기다려요", "L2", null, null, null, null), id -> {});
        UUID legacy = draft("L2");
        jdbc.update("UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?", legacy);

        var result = select(ctx);

        assertThat(result.poolScenarioCount()).isEqualTo(2);
        assertThat(result.candidates())
                .extracting(c -> c.scenarioId())
                .containsExactlyInAnyOrder(own, shared);
        assertThat(select(other).candidates())
                .extracting(c -> c.scenarioId())
                .containsExactlyInAnyOrder(own, shared);
        assertThat(result.candidates()).extracting(c -> c.scenarioId()).doesNotContain(legacy);
    }

    static Stream<Arguments> mismatches() {
        return Stream.of(
                Arguments.of(
                        "GOAL_TEXT_MISMATCH",
                        conditions(
                                "순서를 지켜요",
                                "L2",
                                "CLASS",
                                "SCHOOL",
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "LEVEL_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L1",
                                "CLASS",
                                "SCHOOL",
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "CATEGORY_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "HOME",
                                "SCHOOL",
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "CATEGORY_UNVERIFIED",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                null,
                                "SCHOOL",
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "SITUATION_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "CLASS",
                                "HOME",
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "SITUATION_UNVERIFIED",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "CLASS",
                                null,
                                List.of("아이", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "CHARACTERS_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "CLASS",
                                "SCHOOL",
                                List.of("아이", "친구", "친구"),
                                List.of("기다리기"))),
                Arguments.of(
                        "CHARACTERS_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "CLASS",
                                "SCHOOL",
                                List.of("아이"),
                                List.of("기다리기"))),
                Arguments.of(
                        "CHARACTERS_UNVERIFIED",
                        conditions("차례를 기다려요", "L2", "CLASS", "SCHOOL", null, List.of("기다리기"))),
                Arguments.of(
                        "REQUIRED_ACTIONS_MISMATCH",
                        conditions(
                                "차례를 기다려요",
                                "L2",
                                "CLASS",
                                "SCHOOL",
                                List.of("아이", "친구"),
                                List.of("말하기"))),
                Arguments.of(
                        "REQUIRED_ACTIONS_UNVERIFIED",
                        conditions(
                                "차례를 기다려요", "L2", "CLASS", "SCHOOL", List.of("아이", "친구"), null)));
    }

    @ParameterizedTest
    @MethodSource("mismatches")
    void distinguishesMismatchesFromMissingVerification(
            String reason, ScenarioApprovalConditions confirmed) {
        var ctx = context("CLASS", "SCHOOL", List.of("아이", "친구"), List.of("기다리기"), List.of());
        candidate(ctx, confirmed, id -> {});
        var result = select(ctx);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.exclusionCounts()).containsExactlyEntriesOf(Map.of(reason, 1L));
        assertThat(result.poolScenarioCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_STARTED", "IN_PROGRESS", "PAUSED", "COMPLETED", "RECOVERY_NEEDED"})
    void excludesExactAssignedIdInEveryStatusButAllowsNewEditedVersion(String status) {
        var ctx = context(null, null, List.of(), List.of(), List.of());
        var confirmed = conditions("차례를 기다려요", "L2", null, null, null, null);
        UUID source = candidate(ctx, confirmed, id -> {});
        UUID edited =
                candidate(
                        ctx,
                        confirmed,
                        id ->
                                jdbc.update(
                                        "UPDATE scenario SET source_scenario_id = ?, scenario_version = 2 WHERE scenario_id = ?",
                                        source,
                                        id));
        jdbc.update(
                """
                INSERT INTO activity (activity_id, child_id, goal_id, activity_type, scenario_id, status, assigned_at)
                VALUES (?, ?, ?, 'ROLEPLAY', ?, ?, now())
                """,
                UUID.randomUUID(),
                ctx.child(),
                ctx.goal(),
                source,
                status);

        var result = select(ctx);

        assertThat(result.candidates()).extracting(c -> c.scenarioId()).containsExactly(edited);
        assertThat(result.candidates().getFirst().version()).isEqualTo(2);
        assertThat(result.exclusionCounts())
                .containsExactlyEntriesOf(Map.of("PREVIOUSLY_ASSIGNED", 1L));
    }

    static Stream<Arguments> forbiddenFields() {
        return Stream.of(
                Arguments.of("title = 'Ba Dword'"),
                Arguments.of("summary = 'BA Dword'"),
                Arguments.of("characters = '[{\"id\":\"friend\",\"role\":\"Bad word\"}]'"),
                Arguments.of("scenario_facts = '[\"Bad word\"]'"),
                Arguments.of("opening_prompts = '{\"S0\":\"Bad word\"}'"),
                Arguments.of("micro:description"),
                Arguments.of("micro:required_evidence"),
                Arguments.of("micro:achievement_example"));
    }

    @ParameterizedTest
    @MethodSource("forbiddenFields")
    void checksForbiddenTermsInEveryDisplayedField(String field) {
        var ctx = context(null, null, List.of(), List.of(), List.of("bad WORD"));
        candidate(
                ctx,
                conditions("차례를 기다려요", "L2", null, null, null, null),
                id -> {
                    if (field.startsWith("micro:")) {
                        String column = field.substring(6);
                        String value =
                                column.equals("required_evidence")
                                        ? "'[\"Bad word\"]'"
                                        : "'Bad word'";
                        jdbc.update(
                                """
                        INSERT INTO scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example)
                        VALUES (?, ?, 1, '목표 설명', '["관찰 근거"]', '달성 예시')
                        """,
                                UUID.randomUUID(),
                                id);
                        jdbc.update(
                                "UPDATE scenario_micro_goal SET "
                                        + column
                                        + " = "
                                        + value
                                        + " WHERE scenario_id = ?",
                                id);
                    } else {
                        jdbc.update("UPDATE scenario SET " + field + " WHERE scenario_id = ?", id);
                    }
                });
        var result = select(ctx);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.exclusionCounts())
                .containsExactlyEntriesOf(Map.of("FORBIDDEN_EXPRESSION", 1L));
    }

    @Test
    void doesNotJoinSeparateFieldsOrInspectInternalIdentifiersAndProhibitedInferenceInstructions() {
        var ctx = context(null, null, List.of(), List.of(), List.of("badword"));
        UUID id =
                candidate(
                        ctx,
                        conditions("차례를 기다려요", "L2", null, null, null, null),
                        s ->
                                jdbc.update(
                                        """
                UPDATE scenario SET title = 'bad', summary = 'word',
                    characters = '[{"id":"badword", "role":"친구"}]',
                    prohibited_inferences = '["badword를 추론하지 말 것"]'
                WHERE scenario_id = ?
                """,
                                        s));
        assertThat(select(ctx).candidates()).extracting(c -> c.scenarioId()).containsExactly(id);
    }

    @Test
    void excludesUncheckableContentAndRetiredVersionsWithSeparateReasons() {
        var ctx = context(null, null, List.of(), List.of(), List.of("금지어"));
        var confirmed = conditions("차례를 기다려요", "L2", null, null, null, null);
        candidate(
                ctx,
                confirmed,
                id ->
                        jdbc.update(
                                "UPDATE scenario SET scenario_facts = '{}' WHERE scenario_id = ?",
                                id));
        UUID retired = candidate(ctx, confirmed, id -> {});
        jdbc.update("UPDATE scenario SET status = 'RETIRED' WHERE scenario_id = ?", retired);
        var result = select(ctx);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.exclusionCounts())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("CONTENT_UNVERIFIED", 1L, "NOT_APPROVED", 1L));
    }

    @Test
    void unauthorizedInstructorCannotUseAnotherStudentsGoal() {
        var ctx = context(null, null, List.of(), List.of(), List.of());
        var other = context(null, null, List.of(), List.of(), List.of());
        assertThatThrownBy(() -> service(ctx).select(ctx.activity(), auth(other.instructor())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void filtersMultipleCandidatesInOneContentRead() {
        var ctx = context(null, null, List.of(), List.of(), List.of("금지어"));
        var confirmed = conditions("차례를 기다려요", "L2", null, null, null, null);
        UUID accepted = candidate(ctx, confirmed, id -> {});
        candidate(
                ctx,
                confirmed,
                id ->
                        jdbc.update(
                                "UPDATE scenario SET title = '금지어가 포함된 제목' WHERE scenario_id = ?",
                                id));

        var result = select(ctx);

        assertThat(result.candidates()).extracting(c -> c.scenarioId()).containsExactly(accepted);
        assertThat(result.poolScenarioCount()).isEqualTo(2);
        assertThat(result.exclusionCounts())
                .containsExactlyEntriesOf(Map.of("FORBIDDEN_EXPRESSION", 1L));
    }

    @Test
    void countsOnlyFirstFailurePerCandidate() {
        var ctx = context("CLASS", "SCHOOL", List.of("아이", "친구"), List.of("기다리기"), List.of());
        candidate(ctx, conditions("다른 목표", "L1", null, null, null, null), id -> {});

        var result = select(ctx);

        assertThat(result.poolScenarioCount()).isEqualTo(1);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.exclusionCounts()).containsExactlyEntriesOf(Map.of("LEVEL_MISMATCH", 1L));
    }

    @Test
    void returnsZeroPoolCountWhenNoApprovalExists() {
        var ctx = context(null, null, List.of(), List.of(), List.of());
        var result = select(ctx);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.poolScenarioCount()).isZero();
        assertThat(result.exclusionCounts()).isEmpty();
    }

    private ScenarioCandidateSelection select(Context ctx) {
        return service(ctx).select(ctx.activity(), auth(ctx.instructor()));
    }

    private ScenarioCandidateQueryService service(Context ctx) {
        // 최초 퀴즈 계산은 기존 검사 범위다. 저장된 난이도 계약만 공급하고 권한·목표·후보 조회는 실제로 실행한다.
        var difficulties = mock(InitialDifficultyQueryService.class);
        when(difficulties.requireForActivity(eq(ctx.activity()), any()))
                .thenReturn(
                        new InitialDifficultyEvidence(
                                ctx.activity(),
                                ctx.child(),
                                ctx.goal(),
                                10,
                                5,
                                "L2",
                                "S1",
                                false,
                                null,
                                "INITIAL_QUIZ_V2"));
        var preparation = new InitialScenarioPreparationService(difficulties, goals, children);
        return new ScenarioCandidateQueryService(preparation, candidates, contents);
    }

    private Authentication auth(UUID instructor) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(instructor, UUID.randomUUID(), UserRole.INSTRUCTOR),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INSTRUCTOR")));
    }

    private static ScenarioApprovalConditions conditions(
            String title,
            String level,
            String category,
            String situation,
            List<String> roles,
            List<String> actions) {
        return new ScenarioApprovalConditions(title, level, category, situation, roles, actions);
    }

    private UUID candidate(
            Context ctx, ScenarioApprovalConditions conditions, Consumer<UUID> configure) {
        UUID scenario = draft(conditions.scenarioLevel());
        configure.accept(scenario);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        ignored -> {
                            jdbc.update(
                                    "UPDATE scenario SET status = 'APPROVED' WHERE scenario_id = ?",
                                    scenario);
                            approvals.recordApproval(scenario, ctx.instructor(), conditions);
                        });
        return scenario;
    }

    private UUID draft(String level) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO scenario (scenario_id, status, scenario_level, title, summary, characters,
                    scenario_facts, opening_prompts, origin, created_at)
                VALUES (?, 'DRAFT', ?, '상황 제목', '상황 설명', '[{"id":"friend","role":"친구"}]',
                    '["관찰 사실"]', '{"S0":"어떻게 말할까?"}', 'AI_GENERATED', now())
                """,
                id,
                level);
        return id;
    }

    private Context context(
            String category,
            String situation,
            List<String> roles,
            List<String> actions,
            List<String> forbidden) {
        UUID instructor = UUID.randomUUID();
        UUID classroom = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO user_account (user_id, email, password_hash, name, role, status, created_at)
                VALUES (?, ?, 'test-hash', '검사 강사', 'INSTRUCTOR', 'ACTIVE', now())
                """,
                instructor,
                instructor + "@example.com");
        jdbc.update(
                "INSERT INTO classroom (class_id, instructor_id, name) VALUES (?, ?, '검사 학급')",
                classroom,
                instructor);
        jdbc.update(
                "INSERT INTO child (child_id, class_id, display_name) VALUES (?, ?, '검사 아동')",
                child,
                classroom);
        var characters = roles.stream().map(role -> Map.of("role", role)).toList();
        jdbc.update(
                """
                INSERT INTO learning_goal (goal_id, child_id, instructor_id, title, category, situation_type,
                    characters_json, required_elements_json, forbidden_expressions_json, content_hash, created_at)
                VALUES (?, ?, ?, '차례를 기다려요', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, now())
                """,
                goal,
                child,
                instructor,
                category,
                situation,
                json.writeValueAsString(characters),
                json.writeValueAsString(actions),
                json.writeValueAsString(forbidden),
                "a".repeat(64));
        return new Context(instructor, child, goal, UUID.randomUUID());
    }

    private record Context(UUID instructor, UUID child, UUID goal, UUID activity) {}
}
