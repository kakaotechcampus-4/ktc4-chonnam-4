package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointCommand;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointStore;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputContext;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayRequestIdentity;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationDecision;
import com.neuringo.neuringobe.ai.application.structured.output.EvaluationResult;
import com.neuringo.neuringobe.ai.application.structured.output.GoalObservationOutput;
import com.neuringo.neuringobe.roleplay.application.RoleplayActivityGoalEvaluator;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextFingerprint;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource.Key;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextSource.Snapshot;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationMapper;
import com.neuringo.neuringobe.roleplay.application.RoleplayGoalObservationPlan;
import com.neuringo.neuringobe.roleplay.application.RoleplayProgressSource;
import com.neuringo.neuringobe.roleplay.service.JdbcRoleplayContextSource;
import com.neuringo.neuringobe.roleplay.service.JpaRoleplayCheckpointStore;
import com.neuringo.neuringobe.roleplay.service.RoleplayCompletionService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** 콘텐츠·관찰 조회와 저장은 실제 DB를 사용하고, 아직 없는 지원 정책 공급만 테스트로 대체한다. */
@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayContextSourceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired JdbcRoleplayContextSource source;
    @Autowired RoleplayCompletionService completion;
    @Autowired JpaRoleplayCheckpointStore checkpoints;
    @Autowired RoleplayActivityGoalEvaluator evaluator;
    @Autowired com.neuringo.neuringobe.roleplay.service.RoleplayStartService starts;
    @Autowired com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService scopes;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean RoleplayProgressSource progress;
    private final Map<UUID, Fixture> fixtures = new HashMap<>();

    @BeforeEach
    void explicitTestPolicy() {
        when(progress.load(any(), any())).thenAnswer(call -> policy(call.getArgument(0)));
    }

    @Test
    void retainsTheInputGoalsJudgmentSeparatelyFromTheNextQuestionFocus() {
        var f = fixture();
        var prepared = change(f, load(key(f)).orElseThrow(), false, f.emotion, "S1");
        assertThat(commit(prepared)).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        var saved =
                jdbc.queryForMap(
                        "select input_micro_goal_id,input_learning_state from roleplay_goal_assessment where session_id=?",
                        f.session);
        assertThat(saved)
                .containsEntry("input_micro_goal_id", f.event)
                .containsEntry("input_learning_state", "PARTIAL_UNDERSTANDING");
        assertThat(
                        jdbc.queryForObject(
                                "select micro_goal_id from roleplay_turn where turn_id=?",
                                UUID.class,
                                prepared.command.turnId()))
                .isEqualTo(f.emotion);
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        assertThat(commit(prepared))
                .isInstanceOfSatisfying(
                        RoleplayCheckpointStore.Committed.class,
                        replay -> assertThat(replay.replayed()).isTrue());
        assertThat(
                        jdbc.queryForMap(
                                "select input_micro_goal_id,input_learning_state from roleplay_goal_assessment where session_id=?",
                                f.session))
                .isEqualTo(saved);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_goal_assessment where session_id=?",
                                Integer.class,
                                f.session))
                .isOne();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update roleplay_goal_assessment set input_micro_goal_id=? where session_id=?",
                                        f.emotion,
                                        f.session))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update roleplay_goal_assessment set input_learning_state=null where session_id=?",
                                        f.session))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAPlanThatAssignsTheJudgmentToTheNextGoal() {
        var f = fixture();
        var snapshot = load(key(f)).orElseThrow();
        var prepared = change(f, snapshot, false, f.emotion, "S1");
        var p = prepared.plan.proposal();
        var wrong =
                RoleplayGoalObservationPlan.validateReferences(
                        RoleplayGoalObservationMapper.contextFor(snapshot, prepared.command),
                        new RoleplayGoalObservationPlan.Proposal(
                                p.scope(),
                                p.analysisContractVersion(),
                                p.evaluationContractVersion(),
                                p.observations(),
                                p.nextFocusGoalId(),
                                p.decision(),
                                p.decisionBasis(),
                                new RoleplayGoalObservationPlan.GoalProgress(
                                        f.emotion, "PARTIAL_UNDERSTANDING")));
        assertThat(
                        completion.commitApprovedTurn(
                                prepared.command, wrong, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Conflict.class);
        assertThat(turnCount(f)).isZero();
        assertThat(observationCount(f)).isZero();
    }

    @Test
    void confirmedInputGoalCannotBeChangedBehindItsStoredJudgment() {
        var f = fixture();
        var prepared = change(f, load(key(f)).orElseThrow(), false);
        commit(prepared);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update roleplay_turn set input_micro_goal_id=? where turn_id=?",
                                        f.emotion,
                                        prepared.command.turnId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(load(key(f))).isPresent();
    }

    @Test
    void goalStateSaveFailureRollsBackTheTurnAndSameRequestCanRecover() {
        var f = fixture();
        var prepared = change(f, load(key(f)).orElseThrow(), false);
        jdbc.execute(
                """
                create function test_goal_progress_failure() returns trigger as $$
                begin raise exception '합성 판정 저장 실패' using errcode='23514'; end;
                $$ language plpgsql;
                create trigger test_goal_progress_failure before insert on roleplay_goal_assessment
                for each row when (new.input_learning_state is not null)
                execute function test_goal_progress_failure();
                """);
        try {
            assertThatThrownBy(() -> commit(prepared))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(turnCount(f)).isZero();
            assertThat(observationCount(f)).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from roleplay_goal_assessment where session_id=?",
                                    Integer.class,
                                    f.session))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select row_version from roleplay_session where session_id=?",
                                    Long.class,
                                    f.session))
                    .isZero();
        } finally {
            jdbc.execute(
                    "drop trigger test_goal_progress_failure on roleplay_goal_assessment; drop function test_goal_progress_failure()");
        }
        assertThat(commit(prepared)).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        assertThat(
                        jdbc.queryForObject(
                                "select input_learning_state from roleplay_goal_assessment where session_id=?",
                                String.class,
                                f.session))
                .isEqualTo("PARTIAL_UNDERSTANDING");
    }

    @Test
    void loadsTheActualApprovedVersionBeyondVersionOne() {
        var f =
                fixture(
                        value -> {
                            jdbc.update(
                                    "update scenario set scenario_version=3 where scenario_id=?",
                                    value.scenario);
                            jdbc.update(
                                    "update roleplay_session set scenario_version=3 where session_id=?",
                                    value.session);
                        });
        assertThat(load(key(f))).isPresent();
        assertThat(load(key(f)).orElseThrow().key().scenarioVersion()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"class", "goalHash"})
    void rejectsApprovedContentOutsideTheActivityReuseScope(String changed) {
        var f =
                fixture(
                        value -> {
                            if (changed.equals("class")) {
                                jdbc.update(
                                        "update scenario set class_id=null where scenario_id=?",
                                        value.scenario);
                            } else {
                                jdbc.update(
                                        "update scenario set goal_content_hash=? where scenario_id=?",
                                        "b".repeat(64),
                                        value.scenario);
                            }
                        });
        assertThat(load(key(f))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"facts", "criterion"})
    void databaseRejectsChangesToContentUsedByConfirmedObservations(String changed) {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), false));
        assertThatThrownBy(
                        () -> {
                            if (changed.equals("facts")) {
                                jdbc.update(
                                        "update scenario set scenario_facts='[\"changed\"]'::jsonb where scenario_id=?",
                                        f.scenario);
                            } else {
                                jdbc.update(
                                        "update scenario_micro_goal set required_evidence='[\"changed\"]'::jsonb where micro_goal_id=?",
                                        f.emotion);
                            }
                        })
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(load(key(f))).isPresent();
    }

    @Test
    void startsRestoresAndCompletesThroughTheCombinedStartAndObservationStores() {
        var prepared =
                fixture(
                        value -> {
                            jdbc.update(
                                    "update scenario set opening_prompts='{\"S2\":\"synthetic opening?\"}'::jsonb where scenario_id=?",
                                    value.scenario);
                            jdbc.update(
                                    "insert into scenario_opening_focus(scenario_id,support_level,micro_goal_id) values(?,'S2',?),(?,'S0',?)",
                                    value.scenario,
                                    value.event,
                                    value.scenario,
                                    value.emotion);
                        });
        jdbc.update("delete from roleplay_session where session_id=?", prepared.session);
        jdbc.update(
                "update activity set activity_type='FIRST_COMBINED', initial_support_level='S2', difficulty_fallback_applied=false where activity_id=?",
                prepared.activity);
        jdbc.update(
                """
                insert into quiz_result(activity_id,valid_question_count,correct_question_count,
                    overall_accuracy,total_hint_count,resolved_after_hint_count,scenario_level,
                    initial_support_level,difficulty_fallback_applied,initial_difficulty_used,
                    policy_version,snapshotted_at)
                values(?,1,0,0,0,0,'L1','S2',false,true,'INITIAL_QUIZ_V2',now())
                """,
                prepared.activity);
        var authentication =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(
                                new com.neuringo.neuringobe.child.security.ChildPrincipal(
                                        prepared.child),
                                "",
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("ROLE_CHILD")));
        UUID requestKey = UUID.randomUUID();
        var started = starts.startForDelivery(prepared.activity, requestKey, authentication);
        assertThat(started.created()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select current_micro_goal_id from roleplay_session where session_id=?",
                                UUID.class,
                                started.response().sessionId()))
                .isEqualTo(prepared.event);
        assertThat(started.response().delivery().messages().getFirst().text())
                .isEqualTo("synthetic opening?");
        var f =
                new Fixture(
                        started.response().sessionId(),
                        prepared.activity,
                        prepared.child,
                        prepared.scenario,
                        prepared.goal,
                        prepared.event,
                        prepared.emotion);
        fixtures.put(f.session, f);
        var initial = load(key(f)).orElseThrow();
        assertThat(initial.state().currentSupportLevel()).isEqualTo("S2");
        var first = change(f, initial, false);
        assertThat(commit(first)).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        var restored = starts.startForDelivery(f.activity, UUID.randomUUID(), authentication);
        assertThat(restored.created()).isFalse();
        assertThat(restored.response().delivery().messages().getFirst().purpose())
                .isEqualTo("RESUME");
        assertThat(restored.response().delivery().messages().getFirst().messageId())
                .isEqualTo(first.command.turnId());
        var last = change(f, load(key(f)).orElseThrow(), true);
        assertThat(commit(last)).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        var completed = starts.startForDelivery(f.activity, requestKey, authentication);
        assertThat(completed.response().status()).isEqualTo("COMPLETED");
        assertThat(completed.response().delivery().messages()).isEmpty();
        assertThat(commit(last))
                .isInstanceOfSatisfying(
                        RoleplayCheckpointStore.Committed.class,
                        saved -> assertThat(saved.replayed()).isTrue());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                                Integer.class,
                                f.session))
                .isZero();
    }

    @Test
    void loadsAssignedApprovedContentWithStableEvidenceReferencesAndExplicitPolicy() {
        var f = fixture();
        var first = load(key(f)).orElseThrow();
        var second = load(key(f)).orElseThrow();

        assertThat(first.approvalReference()).isEqualTo(second.approvalReference());
        assertThat(first.observations()).isEqualTo(second.observations());
        assertThat(first.observations().approvedCriteria()).hasSize(2);
        assertThat(first.observations().confirmedObservations()).isEmpty();
        assertThat(first.scenario().microGoals().getFirst().id()).isEqualTo(f.emotion);
        assertThat(first.state().currentMicroGoalId()).isEqualTo(f.event);
        assertThat(first.state().maximumTurnCount()).isEqualTo(77);
        assertThat(first.state().maximumSupportTurnsPerMicroGoal()).isEqualTo(9);
        assertThat(first.toString()).doesNotContain("synthetic activity goal", "emotion evidence");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "child",
                "activity",
                "goal",
                "session",
                "scenario",
                "scenarioVersion",
                "checkpoint"
            })
    void rejectsAnotherScopeOrCheckpoint(String changed) {
        var f = fixture();
        var key = key(f);
        var wrong =
                new Key(
                        changed.equals("child") ? UUID.randomUUID() : key.childId(),
                        changed.equals("activity") ? UUID.randomUUID() : key.activityId(),
                        changed.equals("goal") ? UUID.randomUUID() : key.goalId(),
                        changed.equals("session") ? UUID.randomUUID() : key.sessionId(),
                        changed.equals("scenario") ? UUID.randomUUID() : key.scenarioId(),
                        changed.equals("scenarioVersion") ? 2 : key.scenarioVersion(),
                        changed.equals("checkpoint") ? 1 : key.checkpointVersion());
        assertThat(load(wrong)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "RETIRED"})
    void doesNotExposeContentWithoutCurrentApproval(String status) {
        var f = fixture(value -> {}, status);
        assertThat(load(key(f))).isEmpty();
    }

    @Test
    void restoresCommittedEvidenceAfterCanonicalTextWasErased() {
        var f = fixture();
        var before = load(key(f)).orElseThrow();
        var change = change(f, before, false);
        assertThat(commit(change)).isInstanceOf(RoleplayCheckpointStore.Committed.class);
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        var restored = load(key(f)).orElseThrow();

        assertThat(restored.observations().confirmedObservations()).hasSize(1);
        assertThat(restored.observations().confirmedObservations().getFirst().reference().turnId())
                .isEqualTo(change.command.turnId());
        assertThat(
                        restored.observations()
                                .confirmedObservations()
                                .getFirst()
                                .reference()
                                .microGoalId())
                .isEqualTo(f.emotion);
        assertThat(restored.recentDialogue())
                .extracting(RoleplayTurnInput.DialogueLine::speaker)
                .containsExactly(RoleplayTurnInput.Speaker.AI);
        assertThat(restored.scenario().microGoals())
                .allSatisfy(g -> assertThat(g.status()).isEqualTo("IN_PROGRESS"));
    }

    @Test
    void observationsFromAnotherSessionAreNeverMixedIn() {
        var first = fixture();
        var other = fixture();
        commit(change(first, load(key(first)).orElseThrow(), false));
        assertThat(load(key(first)).orElseThrow().observations().confirmedObservations())
                .hasSize(1);
        assertThat(load(key(other)).orElseThrow().observations().confirmedObservations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "approvalReference", "analysisVersion"})
    void refusesToReinterpretStoredObservationsWithChangedContentOrContract(String changed) {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), false));
        switch (changed) {
            case "goal" ->
                    jdbc.update(
                            "update learning_goal set title='changed goal' where goal_id=?",
                            f.goal);
            case "approvalReference" ->
                    jdbc.update(
                            "update roleplay_goal_assessment set approval_reference='other' where session_id=?",
                            f.session);
            case "analysisVersion" ->
                    jdbc.update(
                            "update roleplay_goal_assessment set analysis_contract_version='analysis-result/v99' where session_id=?",
                            f.session);
            default -> throw new AssertionError();
        }
        assertThat(load(key(f))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "emptyEvidence",
                "duplicateEvidence",
                "numericEvidence",
                "objectFacts",
                "noGoals"
            })
    void malformedApprovedContentDoesNotBecomeAFabricatedContext(String changed) {
        var f =
                fixture(
                        value -> {
                            switch (changed) {
                                case "emptyEvidence" ->
                                        jdbc.update(
                                                "update scenario_micro_goal set required_evidence='[]'::jsonb where micro_goal_id=?",
                                                value.emotion);
                                case "duplicateEvidence" ->
                                        jdbc.update(
                                                "update scenario_micro_goal set required_evidence='[\"same\",\"same\"]'::jsonb where micro_goal_id=?",
                                                value.emotion);
                                case "numericEvidence" ->
                                        jdbc.update(
                                                "update scenario_micro_goal set required_evidence='[1]'::jsonb where micro_goal_id=?",
                                                value.emotion);
                                case "objectFacts" ->
                                        jdbc.update(
                                                "update scenario set scenario_facts='{}'::jsonb where scenario_id=?",
                                                value.scenario);
                                case "noGoals" ->
                                        jdbc.update(
                                                "delete from scenario_micro_goal where scenario_id=?",
                                                value.scenario);
                                default -> throw new AssertionError();
                            }
                        });
        assertThat(load(key(f))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missingPolicy",
                "goalStatus",
                "wrongStatus",
                "turnCount",
                "unknownCurrentGoal"
            })
    void neverFillsMissingPolicyOrProgressWithDefaults(String changed) {
        var f = fixture();
        var k = key(f);
        var base = policy(k).orElseThrow();
        var state = base;
        var statuses = base.microGoalStatuses();
        switch (changed) {
            case "missingPolicy" -> {
                when(progress.load(any(), any())).thenReturn(Optional.empty());
            }
            case "goalStatus" -> statuses = Map.of(f.event, "NOT_OBSERVED");
            case "wrongStatus" -> statuses = Map.of(f.event, "UNKNOWN", f.emotion, "NOT_OBSERVED");
            case "turnCount" ->
                    state = new RoleplayProgressSource.Progress(f.event, "S1", 2, 77, 9, statuses);
            case "unknownCurrentGoal" ->
                    state =
                            new RoleplayProgressSource.Progress(
                                    UUID.randomUUID(), "S1", 1, 77, 9, statuses);
            default -> throw new AssertionError();
        }
        if (!changed.equals("missingPolicy"))
            when(progress.load(any(), any()))
                    .thenReturn(
                            Optional.of(
                                    new RoleplayProgressSource.Progress(
                                            state.currentMicroGoalId(),
                                            state.currentSupportLevel(),
                                            state.turnCount(),
                                            state.maximumTurnCount(),
                                            state.maximumSupportTurnsPerMicroGoal(),
                                            statuses)));
        assertThat(load(k)).isEmpty();
        assertThat(
                        new JdbcRoleplayContextSource(jdbc, json, Optional.empty())
                                .load(k, RoleplayTurnDeadline.start()))
                .isEmpty();
    }

    @Test
    void assemblesOneReadSnapshotEvenWhenAnotherTurnCommitsDuringLoading() {
        var f = fixture();
        var oldKey = key(f);
        var prepared = change(f, load(oldKey).orElseThrow(), false);
        var once = new AtomicBoolean();
        when(progress.load(any(), any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isTrue();
                            assertThat(
                                            jdbc.execute(
                                                    (ConnectionCallback<Integer>)
                                                            Connection::getTransactionIsolation))
                                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                            if (once.compareAndSet(false, true)) commit(prepared);
                            return policy(call.getArgument(0));
                        });
        var oldSnapshot = load(oldKey).orElseThrow();
        assertThat(oldSnapshot.key().checkpointVersion()).isZero();
        assertThat(oldSnapshot.observations().confirmedObservations()).isEmpty();
        assertThat(oldSnapshot.recentDialogue()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(load(oldKey)).isEmpty();
        var latest = load(key(f)).orElseThrow();
        assertThat(latest.observations().confirmedObservations()).hasSize(1);
        assertThat(latest.state().turnCount()).isEqualTo(2);
    }

    @Test
    void completedSessionCannotSupplyANewTurnContext() {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), true));
        assertThat(load(key(f))).isEmpty();
    }

    @Test
    void missingScenarioMigrationDoesNotExposeAnIncompleteContext() {
        var f = fixture();
        var temporary = "test_scenario_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("alter table scenario rename to " + temporary);
        try {
            assertThat(load(key(f))).isEmpty();
        } finally {
            jdbc.execute("alter table " + temporary + " rename to scenario");
        }
    }

    @Test
    void passesTheSameRemainingBudgetToPolicyAndDatabaseReads() {
        var f = fixture();
        var deadline = RoleplayTurnDeadline.start(Duration.ofSeconds(3));
        when(progress.load(any(), any()))
                .thenAnswer(
                        call -> {
                            assertThat((RoleplayTurnDeadline) call.getArgument(1))
                                    .isSameAs(deadline);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select current_setting('statement_timeout')::interval <= interval '3 seconds' and current_setting('statement_timeout')::interval > interval '0 seconds'",
                                                    Boolean.class))
                                    .isTrue();
                            return policy(call.getArgument(0));
                        });
        assertThat(source.load(key(f), deadline)).isPresent();
    }

    @Test
    void expiredBudgetNeverProducesASnapshot() {
        var f = fixture();
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(() -> source.load(key(f), deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @Test
    void slowDatabaseReadIsCancelledWithinTheRemainingBudget() {
        var f = fixture();
        var entered = new AtomicBoolean();
        when(progress.load(any(), any()))
                .thenAnswer(
                        call -> {
                            entered.set(true);
                            jdbc.execute("select pg_sleep(5)");
                            return policy(call.getArgument(0));
                        });
        var deadline = RoleplayTurnDeadline.start(Duration.ofSeconds(1));
        long started = System.nanoTime();
        assertThatThrownBy(() -> source.load(key(f), deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(entered.get()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void turnsWithoutObservationExtensionKeepBoundedDialogueButDoNotInventObservations() {
        var f = fixture();
        for (int i = 0; i < 8; i++) {
            var id = UUID.randomUUID();
            var candidate = candidate(f, id, UUID.randomUUID());
            var analysis =
                    new AnalysisResult(
                            id,
                            "ANSWER_ATTEMPT",
                            "RELEVANT",
                            "PARTIAL_UNDERSTANDING",
                            new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                            new AnalysisResult.NextStrategy("PROBE_EVENT", f.event, null, "S1"),
                            0.9);
            var evaluation =
                    new EvaluationResult(
                            candidate.candidateId(),
                            true,
                            EvaluationDecision.PASS,
                            null,
                            0,
                            List.of(),
                            null);
            var ready = new RoleplayTurnResult.Ready(analysis, candidate, evaluation);
            var command =
                    RoleplayCheckpointCommand.fromApproved(
                            trace(f, id),
                            i,
                            i + 1,
                            UUID.randomUUID(),
                            "a".repeat(64),
                            new RoleplayTurnResult.SpokenReady(
                                    ready,
                                    "synthetic canonical " + i,
                                    null,
                                    null,
                                    RoleplayInputContext.from(load(key(f)).orElseThrow().state())));
            checkpoints.commit(command, RoleplayTurnDeadline.start());
        }
        var snapshot = load(key(f)).orElseThrow();
        assertThat(snapshot.recentDialogue()).hasSize(RoleplayTurnInput.MAX_DIALOGUE_LINES);
        assertThat(snapshot.recentDialogue().getFirst().text()).isEqualTo("synthetic canonical 4");
        assertThat(snapshot.observations().confirmedObservations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualEvaluatorPreparesWithoutWritingThenUsesAtomicCompletion(boolean achieved) {
        var f = fixture();
        var change = change(f, load(key(f)).orElseThrow(), achieved);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var plan = evaluator.evaluate(change.command, change.ready, RoleplayTurnDeadline.start());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(plan.proposal()).isEqualTo(change.plan.proposal());
        assertThat(turnCount(f)).isZero();
        assertThat(observationCount(f)).isZero();
        completion.commitApprovedTurn(change.command, plan, RoleplayTurnDeadline.start());
        assertThat(turnCount(f)).isEqualTo(1);
        assertThat(observationCount(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity))
                .isEqualTo(achieved ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "policy"})
    void changedDataAfterAnalysisCannotBeBoundToTheOldVerdict(String changed) {
        var f = fixture();
        var snapshot = load(key(f)).orElseThrow();
        var change = change(f, snapshot, true);
        switch (changed) {
            case "goal" ->
                    jdbc.update(
                            "update learning_goal set title='changed during analysis' where goal_id=?",
                            f.goal);
            case "policy" ->
                    when(progress.load(any(), any()))
                            .thenAnswer(
                                    call -> {
                                        var old = policy(call.getArgument(0)).orElseThrow();
                                        var s = old;
                                        return Optional.of(
                                                new RoleplayProgressSource.Progress(
                                                        s.currentMicroGoalId(),
                                                        s.currentSupportLevel(),
                                                        s.turnCount(),
                                                        88,
                                                        s.maximumSupportTurnsPerMicroGoal(),
                                                        old.microGoalStatuses()));
                                    });
            default -> throw new AssertionError();
        }
        assertThat(load(key(f))).isPresent();
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        change.command, change.ready, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isZero();
        assertThat(observationCount(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "observation", "responseMetadata", "inputContext"})
    void changedRetainedDialogueOrHistoryCannotReuseTheOldAnalysis(String changed) {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), false));
        var prepared = change(f, load(key(f)).orElseThrow(), true);
        if (changed.equals("canonical"))
            jdbc.update(
                    "update roleplay_turn set canonical_utterance=null where session_id=?",
                    f.session);
        else if (changed.equals("observation"))
            jdbc.update(
                    "update roleplay_goal_observation set observation_status='CONTRADICTORY' where session_id=?",
                    f.session);
        else if (changed.equals("responseMetadata"))
            jdbc.update(
                    "update roleplay_turn set strategy_used='PROBE_EMOTION' where session_id=?",
                    f.session);
        else
            jdbc.update(
                    "update roleplay_turn set input_support_level='S0' where session_id=?",
                    f.session);
        assertThat(load(key(f))).isPresent();
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        prepared.command,
                                        prepared.ready,
                                        RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isEqualTo(1);
        assertThat(observationCount(f)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity))
                .isZero();
    }

    @Test
    void actualEvaluatorCarriesEarlierEvidenceIntoTheFinalDecision() {
        var f = fixture();
        var first = change(f, load(key(f)).orElseThrow(), false);
        var firstPlan =
                evaluator.evaluate(first.command, first.ready, RoleplayTurnDeadline.start());
        completion.commitApprovedTurn(first.command, firstPlan, RoleplayTurnDeadline.start());
        var second = change(f, load(key(f)).orElseThrow(), true);
        var finalPlan =
                evaluator.evaluate(second.command, second.ready, RoleplayTurnDeadline.start());
        assertThat(finalPlan.proposal().decisionBasis()).hasSize(2);
        completion.commitApprovedTurn(second.command, finalPlan, RoleplayTurnDeadline.start());
        assertThat(turnCount(f)).isEqualTo(2);
        assertThat(observationCount(f)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                                Integer.class,
                                f.session))
                .isZero();
    }

    @Test
    void anotherCommittedTurnMakesThePreparedVerdictUnavailable() {
        var f = fixture();
        var snapshot = load(key(f)).orElseThrow();
        var prepared = change(f, snapshot, true);
        commit(change(f, snapshot, false));
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        prepared.command,
                                        prepared.ready,
                                        RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isEqualTo(1);
        assertThat(observationCount(f)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "different"})
    void missingOrDifferentContextFingerprintCannotPrepareADecision(String fingerprint) {
        var f = fixture();
        var change = change(f, load(key(f)).orElseThrow(), true);
        var old = change.command;
        var trace =
                new AiTraceContext(
                        old.requestId(),
                        old.activityId(),
                        null,
                        old.childId(),
                        old.scenarioId(),
                        old.scenarioVersion(),
                        old.sessionId(),
                        old.turnId(),
                        null,
                        old.candidateId());
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        trace,
                        old.expectedVersion(),
                        old.turnNumber(),
                        old.idempotencyKey(),
                        old.inputFingerprint(),
                        new RoleplayTurnResult.SpokenReady(
                                change.ready,
                                old.canonicalUtterance(),
                                null,
                                fingerprint.equals("missing") ? null : "f".repeat(64),
                                old.inputContext()));
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        command, change.ready, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"observation", "basis"})
    void apparentlyApprovedButUnrecognizedEvidenceIsRejectedByTheServer(String changed) {
        var f = fixture();
        var change = change(f, load(key(f)).orElseThrow(), true);
        var old = change.ready.analysis();
        var extension = old.goalObservation();
        var unknown =
                new GoalObservationOutput.Reference(
                        change.command.turnId(), f.emotion, "unknown-evidence");
        var altered =
                new GoalObservationOutput.Analysis(
                        extension.schemaVersion(),
                        extension.analysisId(),
                        changed.equals("observation")
                                ? List.of(
                                        new GoalObservationOutput.Observation(
                                                unknown, GoalObservationOutput.Status.EXPLICIT))
                                : extension.observations(),
                        new GoalObservationOutput.Assessment(
                                GoalObservationOutput.ActivityDecision.ACHIEVED, List.of(unknown)));
        var analysis =
                new AnalysisResult(
                        old.turnId(),
                        old.responseAct(),
                        old.relevance(),
                        old.learningState(),
                        old.primaryGap(),
                        old.nextStrategy(),
                        old.analysisConfidence(),
                        altered);
        var ready =
                new RoleplayTurnResult.Ready(
                        analysis, change.ready.candidate(), change.ready.evaluation());
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        change.command, ready, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isZero();
        assertThat(observationCount(f)).isZero();
    }

    @Test
    void unavailablePolicyAndExpiredBudgetNeverCreateADefaultVerdict() {
        var f = fixture();
        var change = change(f, load(key(f)).orElseThrow(), true);
        when(progress.load(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        change.command, change.ready, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        assertThatThrownBy(() -> evaluator.evaluate(change.command, change.ready, deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
        assertThat(turnCount(f)).isZero();
    }

    @Test
    void restoresEmptyHistoriesWithoutChoosingAGoalFromDisplayOrder() {
        var f = fixture();
        var snapshot = load(key(f)).orElseThrow();
        assertThat(snapshot.responseHistory().byGoal()).containsOnlyKeys(f.event, f.emotion);
        assertThat(snapshot.responseHistory().byGoal().values())
                .allSatisfy(
                        g -> {
                            assertThat(g.responses()).isEmpty();
                            assertThat(g.questionCount()).isZero();
                            assertThat(g.answerRevealed()).isFalse();
                        });
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.event);
        assertThat(snapshot.state().currentMicroGoalPromptCount()).isZero();
        assertThat(snapshot.state().answerRevealed()).isFalse();
    }

    @Test
    void questionHistoryFollowsTheResponseFocusRatherThanTheObservedGoal() {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), false));
        var restored = load(key(f)).orElseThrow();
        assertThat(restored.observations().confirmedObservations())
                .allSatisfy(o -> assertThat(o.reference().microGoalId()).isEqualTo(f.emotion));
        assertThat(restored.responseHistory().byGoal().get(f.event).questionCount()).isEqualTo(1);
        assertThat(restored.responseHistory().byGoal().get(f.emotion).questionCount()).isZero();
        assertThat(restored.state().currentMicroGoalPromptCount()).isEqualTo(1);
        assertThat(restored.responseHistory().inputHistory().exactAttemptCount(f.event))
                .hasValue(1);
        assertThat(restored.responseHistory().inputHistory().exactAttemptCount(f.emotion))
                .hasValue(0);
        assertThat(restored.state().currentMicroGoalSupportTurn()).isEqualTo(2);
    }

    @Test
    void restoresPerGoalResponseTypesSupportHistoryAndRevisitsWithoutLearnerText() {
        var f = fixture();
        var first = recordResponse(f, f.event, "S1", "GUIDING_QUESTION", "PROBE_EVENT");
        recordResponse(f, f.emotion, "S0", "CONFIRMATION", "CONFIRM_UNDERSTANDING");
        recordResponse(f, f.event, "S2", "REDIRECT", "REDIRECT_TO_SCENARIO");
        recordResponse(f, f.event, "S3", "ANSWER_REVEAL", "PROBE_EVENT");
        recordResponse(f, f.emotion, "S1", "CLOSING", "CLOSE_ROLEPLAY");
        var snapshot = load(key(f)).orElseThrow();
        var events = snapshot.responseHistory().byGoal().get(f.event);
        var emotions = snapshot.responseHistory().byGoal().get(f.emotion);
        assertThat(events.questionCount()).isEqualTo(2);
        assertThat(events.answerRevealed()).isTrue();
        assertThat(events.responses()).extracting(r -> r.turnNumber()).containsExactly(1, 3, 4);
        assertThat(events.responses())
                .extracting(r -> r.supportLevel())
                .containsExactly("S1", "S2", "S3");
        assertThat(events.responses().getFirst().candidateId()).isEqualTo(first.candidateId());
        assertThat(emotions.questionCount()).isEqualTo(1);
        assertThat(emotions.answerRevealed()).isFalse();
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.emotion);
        assertThat(snapshot.state().currentMicroGoalPromptCount()).isEqualTo(1);
        assertThat(snapshot.state().answerRevealed()).isFalse();
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        var erased = load(key(f)).orElseThrow();
        assertThat(erased.responseHistory()).isEqualTo(snapshot.responseHistory());
        assertThat(erased.responseHistory().toString())
                .doesNotContain("synthetic canonical", "synthetic approved");
    }

    @Test
    void replayDoesNotCountAnotherQuestionAndAnswerRevealOnlyAppliesToItsGoal() {
        var f = fixture();
        var question = recordResponse(f, f.event, "S1", "GUIDING_QUESTION", "PROBE_EVENT");
        var replay =
                (RoleplayCheckpointStore.Committed)
                        checkpoints.commit(question, RoleplayTurnDeadline.start());
        assertThat(replay.replayed()).isTrue();
        assertThat(load(key(f)).orElseThrow().state().currentMicroGoalPromptCount()).isEqualTo(1);
        recordResponse(f, f.emotion, "S3", "ANSWER_REVEAL", "PROBE_EMOTION");
        var revealed = load(key(f)).orElseThrow();
        assertThat(revealed.state().answerRevealed()).isTrue();
        assertThat(revealed.state().currentMicroGoalPromptCount()).isZero();
        assertThat(revealed.responseHistory().byGoal().get(f.event).answerRevealed()).isFalse();
        recordResponse(f, f.event, "S2", "GUIDING_QUESTION", "PROBE_EVENT");
        var returned = load(key(f)).orElseThrow();
        assertThat(returned.state().currentMicroGoalPromptCount()).isEqualTo(2);
        assertThat(returned.state().answerRevealed()).isFalse();
        assertThat(returned.responseHistory().byGoal().get(f.emotion).answerRevealed()).isTrue();
    }

    @Test
    void unknownLegacyResponseTypesNeverBecomeNoQuestionOrNoAnswerReveal() {
        var f = fixture();
        var command = recordResponse(f, f.event, "S3", "ANSWER_REVEAL", "PROBE_EVENT");
        jdbc.update(
                "update roleplay_turn set response_type=null, strategy_used=null where turn_id=?",
                command.turnId());
        assertThat(load(key(f))).isEmpty();
        assertThat(
                        jdbc.queryForMap(
                                "select response_type, strategy_used from roleplay_turn where turn_id=?",
                                command.turnId()))
                .containsEntry("response_type", null)
                .containsEntry("strategy_used", null);
        assertThat(
                        checkpoints.findConfirmed(
                                new RoleplayRequestIdentity(
                                        trace(f, command.turnId()),
                                        command.idempotencyKey(),
                                        command.inputFingerprint(),
                                        command.expectedVersion(),
                                        command.turnNumber()),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Found.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missingTurn",
                "turnGap",
                "futureCheckpoint",
                "unapprovedGoal",
                "lastGoal",
                "lastSupport",
                "lastId"
            })
    void incompleteOrInconsistentResponseHistoriesDoNotBecomeAContext(String changed) {
        var f = fixture();
        var first = recordResponse(f, f.event, "S1", "GUIDING_QUESTION", "PROBE_EVENT");
        var last = recordResponse(f, f.emotion, "S1", "GUIDING_QUESTION", "PROBE_EMOTION");
        switch (changed) {
            case "missingTurn" ->
                    jdbc.update("delete from roleplay_turn where turn_id=?", first.turnId());
            case "turnGap" ->
                    jdbc.update(
                            "update roleplay_turn set turn_number=7 where turn_id=?",
                            first.turnId());
            case "futureCheckpoint" ->
                    jdbc.update(
                            "update roleplay_turn set checkpoint_version=99 where turn_id=?",
                            last.turnId());
            case "unapprovedGoal" ->
                    jdbc.update(
                            "update roleplay_turn set micro_goal_id=? where turn_id=?",
                            UUID.randomUUID(),
                            first.turnId());
            case "lastGoal" ->
                    jdbc.update(
                            "update roleplay_session set current_micro_goal_id=? where session_id=?",
                            f.event,
                            f.session);
            case "lastSupport" ->
                    jdbc.update(
                            "update roleplay_session set current_support_level='S0' where session_id=?",
                            f.session);
            case "lastId" ->
                    jdbc.update(
                            "update roleplay_session set last_turn_id=? where session_id=?",
                            first.turnId(),
                            f.session);
            default -> throw new AssertionError();
        }
        assertThat(load(key(f))).isEmpty();
    }

    private RoleplayCheckpointCommand recordResponse(
            Fixture f, UUID goal, String support, String type, String strategy) {
        var key = key(f);
        var input = RoleplayInputContext.from(load(key).orElseThrow().state());
        int next =
                jdbc.queryForObject(
                        "select last_turn_number+1 from roleplay_session where session_id=?",
                        Integer.class,
                        f.session);
        var trace = trace(f, UUID.randomUUID());
        var candidate =
                new CandidateResponse(
                        UUID.randomUUID(),
                        trace.turnId(),
                        "synthetic approved response",
                        type,
                        strategy,
                        goal,
                        support,
                        List.of());
        var analysis =
                new AnalysisResult(
                        trace.turnId(),
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("NONE", null),
                        new AnalysisResult.NextStrategy(strategy, goal, null, support),
                        0.9);
        var review =
                new EvaluationResult(
                        candidate.candidateId(),
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null);
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        trace,
                        key.checkpointVersion(),
                        next,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        new RoleplayTurnResult.SpokenReady(
                                new RoleplayTurnResult.Ready(analysis, candidate, review),
                                "synthetic canonical",
                                null,
                                null,
                                input));
        assertThat(checkpoints.commit(command, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Committed.class);
        return command;
    }

    @Test
    void actualEvaluatorStoresInputGoalAndSupportSeparatelyFromTheNextQuestion() {
        var f = fixture();
        var before = load(key(f)).orElseThrow();
        var first = change(f, before, false, f.emotion, "S2");
        var plan = evaluator.evaluate(first.command, first.ready, RoleplayTurnDeadline.start());
        completion.commitApprovedTurn(first.command, plan, RoleplayTurnDeadline.start());
        var row = inputMetadata(first.command.turnId());
        assertThat(row)
                .containsEntry("input_micro_goal_id", f.event)
                .containsEntry("input_support_level", "S1")
                .containsEntry("input_support_turn", 1)
                .containsEntry("input_prompt_count", 0)
                .containsEntry("input_answer_revealed", false);
        assertThat(
                        jdbc.queryForMap(
                                "select micro_goal_id, support_level from roleplay_turn where turn_id=?",
                                first.command.turnId()))
                .containsEntry("micro_goal_id", f.emotion)
                .containsEntry("support_level", "S2");
        var next = load(key(f)).orElseThrow();
        var response = next.responseHistory().byGoal().get(f.emotion).responses().getFirst();
        assertThat(response.inputContext()).isEqualTo(RoleplayInputContext.from(before.state()));
        assertThat(next.state().currentMicroGoalPromptCount()).isEqualTo(1);
        var observationContext = next.observations().confirmedObservationContexts().getFirst();
        assertThat(observationContext.reference().microGoalId()).isEqualTo(f.emotion);
        assertThat(observationContext.reference().turnId()).isEqualTo(first.command.turnId());
        assertThat(observationContext.candidateId()).isEqualTo(first.command.candidateId());
        assertThat(observationContext.inputContext())
                .isEqualTo(RoleplayInputContext.from(before.state()));
        assertThat(observationContext.inputContext().microGoalId()).isEqualTo(f.event);
        assertThat(observationContext.inputContext().supportLevel()).isEqualTo("S1");
        assertThat(observationContext.goalExposureBeforeInput().questionCount()).isZero();
        assertThat(observationContext.goalExposureBeforeInput().answerRevealed()).isFalse();
        assertThat(observationContext.goalExposureBeforeInput().lastResponse()).isNull();
        var finalTurn = change(f, next, true);
        completion.commitApprovedTurn(
                finalTurn.command,
                evaluator.evaluate(
                        finalTurn.command, finalTurn.ready, RoleplayTurnDeadline.start()),
                RoleplayTurnDeadline.start());
        assertThat(inputMetadata(first.command.turnId())).isEqualTo(row);
        assertThat(inputMetadata(finalTurn.command.turnId()))
                .containsEntry("input_micro_goal_id", f.emotion)
                .containsEntry("input_support_level", "S2")
                .containsEntry("input_prompt_count", 1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=? and canonical_utterance is not null",
                                Integer.class,
                                f.session))
                .isZero();
        var replay =
                (RoleplayCheckpointStore.Committed)
                        completion.commitApprovedTurn(
                                finalTurn.command, finalTurn.plan, RoleplayTurnDeadline.start());
        assertThat(replay.replayed()).isTrue();
        assertThat(inputMetadata(finalTurn.command.turnId()))
                .containsEntry("input_prompt_count", 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "goal",
                "support",
                "supportTurn",
                "promptCount",
                "answerRevealed"
            })
    void validContextFingerprintCannotAuthorizeDifferentInputMetadata(String changed) {
        var f = fixture();
        var prepared = change(f, load(key(f)).orElseThrow(), false);
        var old = prepared.command;
        var input = old.inputContext();
        var altered =
                changed.equals("missing")
                        ? null
                        : new RoleplayInputContext(
                                changed.equals("goal") ? f.emotion : input.microGoalId(),
                                changed.equals("support") ? "S2" : input.supportLevel(),
                                changed.equals("supportTurn") ? 99 : input.supportTurn(),
                                changed.equals("promptCount") ? 99 : input.promptCount(),
                                changed.equals("answerRevealed") || input.answerRevealed());
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        trace(f, old.turnId()),
                        old.expectedVersion(),
                        old.turnNumber(),
                        old.idempotencyKey(),
                        old.inputFingerprint(),
                        new RoleplayTurnResult.SpokenReady(
                                prepared.ready,
                                old.canonicalUtterance(),
                                null,
                                old.approvedContextFingerprint(),
                                altered));
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        command, prepared.ready, RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isZero();
        assertThat(observationCount(f)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"support", "questions", "revealed", "supportTurn"})
    void inputMetadataMustMatchThePreviouslyConfirmedQuestionHistory(String changed) {
        var f = fixture();
        commit(change(f, load(key(f)).orElseThrow(), false));
        var second = change(f, load(key(f)).orElseThrow(), false);
        commit(second);
        switch (changed) {
            case "support" ->
                    jdbc.update(
                            "update roleplay_turn set input_support_level='S2' where turn_id=?",
                            second.command.turnId());
            case "questions" ->
                    jdbc.update(
                            "update roleplay_turn set input_prompt_count=99 where turn_id=?",
                            second.command.turnId());
            case "supportTurn" ->
                    jdbc.update(
                            "update roleplay_turn set input_support_turn=99 where turn_id=?",
                            second.command.turnId());
            case "revealed" ->
                    jdbc.update(
                            "update roleplay_turn set input_answer_revealed=true where turn_id=?",
                            second.command.turnId());
            default -> throw new AssertionError();
        }
        assertThat(load(key(f))).isEmpty();
    }

    @Test
    void legacyInputContextBlocksInventedAttemptCountsButConfirmedReplayIsAvailable() {
        var f = fixture();
        var saved = recordResponse(f, f.emotion, "S2", "GUIDING_QUESTION", "PROBE_EMOTION");
        jdbc.update(
                "update roleplay_turn set input_micro_goal_id=null, input_support_level=null, input_support_turn=null, input_prompt_count=null, input_answer_revealed=null where turn_id=?",
                saved.turnId());
        assertThat(load(key(f))).isEmpty();
        assertThat(inputMetadata(saved.turnId())).containsEntry("input_micro_goal_id", null);
        assertThat(
                        checkpoints.findConfirmed(
                                new RoleplayRequestIdentity(
                                        trace(f, saved.turnId()),
                                        saved.idempotencyKey(),
                                        saved.inputFingerprint(),
                                        saved.expectedVersion(),
                                        saved.turnNumber()),
                                RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayCheckpointStore.Found.class);
    }

    @Test
    void inputMetadataRollsBackWithTheTurnAndSameKeyCanBeRetried() {
        var f = fixture();
        var prepared = change(f, load(key(f)).orElseThrow(), false);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            assertThat(
                                                            checkpoints.commitInCurrentTransaction(
                                                                    prepared.command,
                                                                    RoleplayTurnDeadline.start()))
                                                    .isInstanceOf(
                                                            RoleplayCheckpointStore.Committed
                                                                    .class);
                                            throw new IllegalStateException(
                                                    "synthetic outer transaction failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(turnCount(f)).isZero();
        assertThat(load(key(f)).orElseThrow().state().currentMicroGoalSupportTurn()).isEqualTo(1);
        commit(prepared);
        assertThat(load(key(f)).orElseThrow().state().currentMicroGoalSupportTurn()).isEqualTo(2);
        assertThat(inputMetadata(prepared.command.turnId()))
                .containsEntry("input_micro_goal_id", f.event)
                .containsEntry("input_support_level", "S1");
    }

    @Test
    void preservesWhetherTheAnswerWasAlreadyRevealedBeforeTheChildResponded() {
        var f = fixture();
        recordResponse(f, f.emotion, "S3", "ANSWER_REVEAL", "PROBE_EMOTION");
        var snapshot = load(key(f)).orElseThrow();
        assertThat(snapshot.state().answerRevealed()).isTrue();
        var prepared = change(f, snapshot, false, f.event, "S2");
        completion.commitApprovedTurn(
                prepared.command,
                evaluator.evaluate(prepared.command, prepared.ready, RoleplayTurnDeadline.start()),
                RoleplayTurnDeadline.start());
        assertThat(inputMetadata(prepared.command.turnId()))
                .containsEntry("input_micro_goal_id", f.emotion)
                .containsEntry("input_support_level", "S3")
                .containsEntry("input_answer_revealed", true);
        var restored = load(key(f)).orElseThrow();
        assertThat(
                        restored.responseHistory()
                                .byGoal()
                                .get(f.event)
                                .responses()
                                .getFirst()
                                .inputContext()
                                .answerRevealed())
                .isTrue();
        var context = restored.observations().confirmedObservationContexts().getFirst();
        assertThat(context.inputContext().microGoalId()).isEqualTo(f.emotion);
        assertThat(context.goalExposureBeforeInput().answerRevealed()).isTrue();
        assertThat(context.goalExposureBeforeInput().lastResponse().microGoalId())
                .isEqualTo(f.emotion);
        assertThat(context.goalExposureBeforeInput().lastResponse().supportLevel()).isEqualTo("S3");
        assertThat(context.goalExposureBeforeInput().lastResponse().responseType())
                .isEqualTo("ANSWER_REVEAL");
        assertThat(context.goalExposureBeforeInput().lastResponse().turnNumber()).isEqualTo(1);
    }

    @Test
    void actualContextRestoresPerGoalAttemptCountsAndSupportTurnsAcrossFocusChanges() {
        var f = fixture();
        var initial = load(key(f)).orElseThrow();
        assertThat(initial.state().currentMicroGoalSupportTurn()).isEqualTo(1);
        var first = change(f, initial, false, f.emotion, "S2");
        commit(first);
        var afterFirst = load(key(f)).orElseThrow();
        assertThat(afterFirst.responseHistory().inputHistory().exactAttemptCount(f.event))
                .hasValue(1);
        assertThat(afterFirst.responseHistory().inputHistory().exactAttemptCount(f.emotion))
                .hasValue(0);
        assertThat(afterFirst.state().currentMicroGoalId()).isEqualTo(f.emotion);
        assertThat(afterFirst.state().currentMicroGoalPromptCount()).isEqualTo(1);
        assertThat(afterFirst.state().currentMicroGoalSupportTurn()).isEqualTo(1);
        var prepared =
                new com.neuringo.neuringobe.roleplay.application.RoleplayContextAssembler(source)
                        .prepareVoice(
                                scopes.load(f.child, f.activity, f.session),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                RoleplayTurnDeadline.start());
        assertThat(prepared.input().state().currentMicroGoalSupportTurn()).isEqualTo(1);
        var second = change(f, afterFirst, false, f.event, "S1");
        commit(second);
        var returned = load(key(f)).orElseThrow();
        assertThat(returned.state().turnCount()).isEqualTo(3);
        assertThat(returned.state().currentMicroGoalSupportTurn()).isEqualTo(2);
        assertThat(returned.responseHistory().inputHistory().exactAttemptCount(f.emotion))
                .hasValue(1);
        var third = change(f, returned, false, f.emotion, "S2");
        commit(third);
        var finalSnapshot = load(key(f)).orElseThrow();
        assertThat(finalSnapshot.responseHistory().inputHistory().exactAttemptCount(f.event))
                .hasValue(2);
        assertThat(finalSnapshot.state().currentMicroGoalSupportTurn()).isEqualTo(2);
        assertThat(inputMetadata(first.command.turnId())).containsEntry("input_support_turn", 1);
        assertThat(inputMetadata(second.command.turnId())).containsEntry("input_support_turn", 1);
        assertThat(inputMetadata(third.command.turnId())).containsEntry("input_support_turn", 2);
        var replay = (RoleplayCheckpointStore.Committed) commit(third);
        assertThat(replay.replayed()).isTrue();
        assertThat(load(key(f)).orElseThrow().responseHistory().inputHistory())
                .isEqualTo(finalSnapshot.responseHistory().inputHistory());
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        assertThat(load(key(f)).orElseThrow().responseHistory().inputHistory())
                .isEqualTo(finalSnapshot.responseHistory().inputHistory());
    }

    @Test
    void laterAnswerRevealAndTextErasureNeverRewriteAnEarlierObservationContext() {
        var f = fixture();
        var first = change(f, load(key(f)).orElseThrow(), false, f.emotion, "S2");
        completion.commitApprovedTurn(
                first.command,
                evaluator.evaluate(first.command, first.ready, RoleplayTurnDeadline.start()),
                RoleplayTurnDeadline.start());
        var before = load(key(f)).orElseThrow().observations().confirmedObservationContexts();
        assertThat(before).hasSize(1);
        recordResponse(f, f.emotion, "S3", "ANSWER_REVEAL", "PROBE_EMOTION");
        var after = load(key(f)).orElseThrow();
        assertThat(after.state().answerRevealed()).isTrue();
        assertThat(after.observations().confirmedObservationContexts()).isEqualTo(before);
        assertThat(before.getFirst().goalExposureBeforeInput().answerRevealed()).isFalse();
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        assertThat(load(key(f)).orElseThrow().observations().confirmedObservationContexts())
                .isEqualTo(before);
    }

    @Test
    void actualEvaluatorStoresTwoGoalObservationsAgainstOneInputAndOneAttempt() {
        var f = fixture();
        var snapshot = load(key(f)).orElseThrow();
        var prepared = change(f, snapshot, false, f.emotion, "S2");
        var old = prepared.ready.analysis();
        var eventCriterion =
                snapshot.observations().approvedCriteria().stream()
                        .filter(c -> c.microGoalId().equals(f.event))
                        .findFirst()
                        .orElseThrow();
        var observations = new ArrayList<>(old.goalObservation().observations());
        observations.add(
                new GoalObservationOutput.Observation(
                        new GoalObservationOutput.Reference(
                                prepared.command.turnId(), f.event, eventCriterion.reference()),
                        GoalObservationOutput.Status.EXPLICIT));
        var analysis =
                new AnalysisResult(
                        old.turnId(),
                        old.responseAct(),
                        old.relevance(),
                        old.learningState(),
                        old.primaryGap(),
                        old.nextStrategy(),
                        old.analysisConfidence(),
                        new GoalObservationOutput.Analysis(
                                old.goalObservation().schemaVersion(),
                                old.goalObservation().analysisId(),
                                observations,
                                old.goalObservation().activityGoal()));
        var ready =
                new RoleplayTurnResult.Ready(
                        analysis, prepared.ready.candidate(), prepared.ready.evaluation());
        completion.commitApprovedTurn(
                prepared.command,
                evaluator.evaluate(prepared.command, ready, RoleplayTurnDeadline.start()),
                RoleplayTurnDeadline.start());
        var restored = load(key(f)).orElseThrow();
        assertThat(restored.observations().confirmedObservationContexts())
                .hasSize(2)
                .allSatisfy(
                        context -> {
                            assertThat(context.reference().turnId())
                                    .isEqualTo(prepared.command.turnId());
                            assertThat(context.candidateId())
                                    .isEqualTo(prepared.command.candidateId());
                            assertThat(context.inputContext())
                                    .isEqualTo(RoleplayInputContext.from(snapshot.state()));
                            assertThat(context.inputContext().microGoalId()).isEqualTo(f.event);
                        });
        assertThat(observationCount(f)).isEqualTo(2);
        assertThat(restored.responseHistory().inputHistory().exactAttemptCount(f.event))
                .hasValue(1);
        assertThat(restored.responseHistory().inputHistory().exactAttemptCount(f.emotion))
                .hasValue(0);
    }

    @Test
    void currentInputCarriesOtherGoalsEarlierAnswerRevealThroughTheActualAssembler() {
        var f = fixture();
        var answer = recordResponse(f, f.emotion, "S3", "ANSWER_REVEAL", "PROBE_EMOTION");
        recordResponse(f, f.event, "S1", "GUIDING_QUESTION", "PROBE_EVENT");
        var snapshot = load(key(f)).orElseThrow();
        assertThat(snapshot.state().currentMicroGoalId()).isEqualTo(f.event);
        assertThat(snapshot.state().answerRevealed()).isFalse();
        var emotion =
                snapshot.observations().currentGoalExposures().stream()
                        .filter(e -> e.microGoalId().equals(f.emotion))
                        .findFirst()
                        .orElseThrow()
                        .exposureBeforeInput();
        assertThat(emotion.answerRevealed()).isTrue();
        assertThat(emotion.lastResponse().turnId()).isEqualTo(answer.turnId());
        assertThat(emotion.lastResponse().candidateId()).isEqualTo(answer.candidateId());
        assertThat(emotion.lastResponse().supportLevel()).isEqualTo("S3");
        var prepared =
                new com.neuringo.neuringobe.roleplay.application.RoleplayContextAssembler(source)
                        .prepareVoice(
                                scopes.load(f.child, f.activity, f.session),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                RoleplayTurnDeadline.start());
        assertThat(prepared.observations().currentGoalExposures())
                .isEqualTo(snapshot.observations().currentGoalExposures());
        var change = change(f, snapshot, false);
        completion.commitApprovedTurn(
                change.command,
                evaluator.evaluate(change.command, change.ready, RoleplayTurnDeadline.start()),
                RoleplayTurnDeadline.start());
        var next = load(key(f)).orElseThrow();
        assertThat(
                        next.observations().currentGoalExposures().stream()
                                .filter(e -> e.microGoalId().equals(f.event))
                                .findFirst()
                                .orElseThrow()
                                .exposureBeforeInput()
                                .questionCount())
                .isEqualTo(2);
        assertThat(
                        snapshot.observations().currentGoalExposures().stream()
                                .filter(e -> e.microGoalId().equals(f.event))
                                .findFirst()
                                .orElseThrow()
                                .exposureBeforeInput()
                                .questionCount())
                .isEqualTo(1);
        jdbc.update(
                "update roleplay_turn set canonical_utterance=null where session_id=?", f.session);
        assertThat(load(key(f)).orElseThrow().observations().currentGoalExposures())
                .isEqualTo(next.observations().currentGoalExposures());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sameGoalLowerSupport", "revisitAnsweredGoal"})
    void approvedModelVerdictCannotBypassTheActualServersSupportHistoryGuard(String changed) {
        var f = fixture();
        recordResponse(
                f,
                f.emotion,
                changed.equals("revisitAnsweredGoal") ? "S3" : "S2",
                changed.equals("revisitAnsweredGoal") ? "ANSWER_REVEAL" : "GUIDING_QUESTION",
                "PROBE_EMOTION");
        if (changed.equals("revisitAnsweredGoal"))
            recordResponse(f, f.event, "S1", "GUIDING_QUESTION", "PROBE_EVENT");
        var snapshot = load(key(f)).orElseThrow();
        var before = turnCount(f);
        var proposed = change(f, snapshot, false, f.emotion, "S1");
        assertThat(proposed.ready.evaluation().canDeliver()).isTrue();
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        proposed.command,
                                        proposed.ready,
                                        RoleplayTurnDeadline.start()))
                .isInstanceOf(RoleplayActivityGoalEvaluator.Unavailable.class);
        assertThat(turnCount(f)).isEqualTo(before);
        assertThat(observationCount(f)).isZero();
        assertThat(load(key(f)).orElseThrow()).isEqualTo(snapshot);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from learning_record where activity_id=?",
                                Integer.class,
                                f.activity))
                .isZero();
    }

    @Test
    void actualServerAllowsALowerStartingQuestionForADifferentGoal() {
        var f = fixture();
        recordResponse(f, f.emotion, "S2", "GUIDING_QUESTION", "PROBE_EMOTION");
        var proposed = change(f, load(key(f)).orElseThrow(), false, f.event, "S0");
        var plan =
                evaluator.evaluate(proposed.command, proposed.ready, RoleplayTurnDeadline.start());
        completion.commitApprovedTurn(proposed.command, plan, RoleplayTurnDeadline.start());
        assertThat(load(key(f)).orElseThrow().state().currentMicroGoalId()).isEqualTo(f.event);
        assertThat(load(key(f)).orElseThrow().state().currentSupportLevel()).isEqualTo("S0");
    }

    private Map<String, Object> inputMetadata(UUID turn) {
        return jdbc.queryForMap(
                "select input_micro_goal_id, input_support_level, input_support_turn, input_prompt_count, input_answer_revealed from roleplay_turn where turn_id=?",
                turn);
    }

    private int turnCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?", Integer.class, f.session);
    }

    private int observationCount(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_goal_observation where session_id=?",
                Integer.class,
                f.session);
    }

    private Optional<Snapshot> load(Key key) {
        return source.load(key, RoleplayTurnDeadline.start());
    }

    private Optional<RoleplayProgressSource.Progress> policy(Key key) {
        if (key == null) return Optional.empty();
        var f = fixtures.get(key.sessionId());
        if (f == null) return Optional.empty();
        var row =
                jdbc.queryForMap(
                        "select last_turn_number, current_micro_goal_id, current_support_level from roleplay_session where session_id=?",
                        key.sessionId());
        int turn = ((Number) row.get("last_turn_number")).intValue();
        return Optional.of(
                new RoleplayProgressSource.Progress(
                        row.get("current_micro_goal_id") == null
                                ? f.event
                                : (UUID) row.get("current_micro_goal_id"),
                        row.get("current_support_level") == null
                                ? "S1"
                                : row.get("current_support_level").toString(),
                        turn + 1,
                        77,
                        9,
                        Map.of(
                                f.event,
                                turn == 0 ? "NOT_OBSERVED" : "IN_PROGRESS",
                                f.emotion,
                                turn == 0 ? "NOT_OBSERVED" : "IN_PROGRESS")));
    }

    private Change change(Fixture f, Snapshot snapshot, boolean achieved) {
        return change(f, snapshot, achieved, f.event, "S1");
    }

    private Change change(
            Fixture f, Snapshot snapshot, boolean achieved, UUID nextFocus, String nextSupport) {
        UUID turn = UUID.randomUUID(),
                candidateId = UUID.randomUUID(),
                analysisId = UUID.randomUUID();
        var criterion =
                snapshot.observations().approvedCriteria().stream()
                        .filter(c -> c.microGoalId().equals(f.emotion))
                        .findFirst()
                        .orElseThrow();
        var ref =
                new GoalObservationOutput.Reference(
                        turn, criterion.microGoalId(), criterion.reference());
        var basis = new ArrayList<GoalObservationOutput.Reference>();
        basis.add(ref);
        if (achieved)
            snapshot.observations().confirmedObservations().forEach(o -> basis.add(o.reference()));
        var extension =
                new GoalObservationOutput.Analysis(
                        GoalObservationOutput.ANALYSIS_VERSION,
                        analysisId,
                        List.of(
                                new GoalObservationOutput.Observation(
                                        ref, GoalObservationOutput.Status.EXPLICIT)),
                        new GoalObservationOutput.Assessment(
                                achieved
                                        ? GoalObservationOutput.ActivityDecision.ACHIEVED
                                        : GoalObservationOutput.ActivityDecision.IN_PROGRESS,
                                basis));
        var analysis =
                new AnalysisResult(
                        turn,
                        "ANSWER_ATTEMPT",
                        "RELEVANT",
                        "PARTIAL_UNDERSTANDING",
                        new AnalysisResult.PrimaryGap("MISSING_EMOTION", null),
                        new AnalysisResult.NextStrategy(
                                "PROBE_EVENT", nextFocus, null, nextSupport),
                        0.9,
                        extension);
        var evaluation =
                new EvaluationResult(
                        candidateId,
                        true,
                        EvaluationDecision.PASS,
                        null,
                        0,
                        List.of(),
                        null,
                        new GoalObservationOutput.Review(
                                GoalObservationOutput.EVALUATION_VERSION, analysisId, true, true));
        var ready =
                new RoleplayTurnResult.Ready(
                        analysis,
                        new CandidateResponse(
                                candidateId,
                                turn,
                                "synthetic approved response?",
                                "GUIDING_QUESTION",
                                "PROBE_EVENT",
                                nextFocus,
                                nextSupport,
                                List.of()),
                        evaluation);
        var command =
                RoleplayCheckpointCommand.fromApproved(
                        trace(f, turn),
                        snapshot.key().checkpointVersion(),
                        snapshot.state().turnCount(),
                        UUID.randomUUID(),
                        "a".repeat(64),
                        new RoleplayTurnResult.SpokenReady(
                                ready,
                                "synthetic canonical",
                                null,
                                RoleplayContextFingerprint.of(snapshot),
                                RoleplayInputContext.from(snapshot.state())));
        var criteria = new HashMap<UUID, Set<String>>();
        snapshot.observations()
                .approvedCriteria()
                .forEach(
                        c ->
                                criteria.computeIfAbsent(
                                                c.microGoalId(), ignored -> new HashSet<>())
                                        .add(c.reference()));
        var history =
                snapshot.observations().confirmedObservations().stream()
                        .map(
                                o ->
                                        new RoleplayGoalObservationPlan.Observation(
                                                new RoleplayGoalObservationPlan.EvidenceReference(
                                                        o.reference().turnId(),
                                                        o.reference().microGoalId(),
                                                        o.reference().criterionReference()),
                                                RoleplayGoalObservationPlan.ObservationStatus
                                                        .valueOf(o.status().name())))
                        .toList();
        var context =
                new RoleplayGoalObservationPlan.Context(
                        new RoleplayGoalObservationPlan.Scope(
                                f.session,
                                f.scenario,
                                1,
                                snapshot.approvalReference(),
                                turn,
                                candidateId,
                                snapshot.key().checkpointVersion()),
                        criteria,
                        history,
                        GoalObservationOutput.ANALYSIS_VERSION,
                        GoalObservationOutput.EVALUATION_VERSION);
        return new Change(
                command, RoleplayGoalObservationMapper.toPlan(context, command, ready), ready);
    }

    private RoleplayCheckpointStore.CommitResult commit(Change change) {
        return completion.commitApprovedTurn(
                change.command, change.plan, RoleplayTurnDeadline.start());
    }

    private CandidateResponse candidate(Fixture f, UUID turn, UUID candidate) {
        return new CandidateResponse(
                candidate,
                turn,
                "synthetic approved response?",
                "GUIDING_QUESTION",
                "PROBE_EVENT",
                f.event,
                "S1",
                List.of());
    }

    private AiTraceContext trace(Fixture f, UUID turn) {
        return new AiTraceContext(
                UUID.randomUUID(),
                f.activity,
                null,
                f.child,
                f.scenario,
                1,
                f.session,
                turn,
                null,
                null);
    }

    private Key key(Fixture f) {
        var version =
                jdbc.queryForObject(
                        "select row_version from roleplay_session where session_id=?",
                        Long.class,
                        f.session);
        return new Key(
                f.child,
                f.activity,
                f.goal,
                f.session,
                f.scenario,
                jdbc.queryForObject(
                        "select scenario_version from roleplay_session where session_id=?",
                        Integer.class,
                        f.session),
                version);
    }

    private Fixture fixture() {
        return fixture(value -> {}, "APPROVED");
    }

    private Fixture fixture(java.util.function.Consumer<Fixture> prepare) {
        return fixture(prepare, "APPROVED");
    }

    private Fixture fixture(java.util.function.Consumer<Fixture> prepare, String status) {
        UUID instructor = UUID.randomUUID(), classroom = UUID.randomUUID();
        var f =
                new Fixture(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID());
        var now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                instructor,
                instructor + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                instructor);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                f.child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic activity goal',?,?)",
                f.goal,
                f.child,
                instructor,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,activity_type,status,assigned_at) values(?,?,?,?,'ROLEPLAY','IN_PROGRESS',?)",
                f.activity,
                f.child,
                f.goal,
                f.scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                f.session,
                f.activity,
                f.child,
                f.scenario,
                now);
        jdbc.update(
                "insert into scenario(scenario_id,class_id,goal_content_hash,status,scenario_level,title,ai_role,scenario_facts,origin,created_at) values(?,?,?,'DRAFT','L1','synthetic scenario','NARRATOR','[\"synthetic fact\"]'::jsonb,'PREPARED',?)",
                f.scenario,
                classroom,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,2,'synthetic event','[\"event evidence\"]'::jsonb)",
                f.event,
                f.scenario);
        jdbc.update(
                "insert into scenario_micro_goal(micro_goal_id,scenario_id,order_no,description,required_evidence) values(?,?,1,'synthetic emotion','[\"emotion evidence\"]'::jsonb)",
                f.emotion,
                f.scenario);
        prepare.accept(f);
        jdbc.update("update scenario set status=? where scenario_id=?", status, f.scenario);
        fixtures.put(f.session, f);
        return f;
    }

    private record Fixture(
            UUID session,
            UUID activity,
            UUID child,
            UUID scenario,
            UUID goal,
            UUID event,
            UUID emotion) {}

    private record Change(
            RoleplayCheckpointCommand command,
            RoleplayGoalObservationPlan plan,
            RoleplayTurnResult.Ready ready) {}
}
