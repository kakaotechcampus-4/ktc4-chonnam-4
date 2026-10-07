package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.roleplay.application.*;
import com.neuringo.neuringobe.roleplay.application.RoleplayUploadPolicy;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus;
import com.neuringo.neuringobe.roleplay.service.RoleplayCanonicalRetentionService;
import com.neuringo.neuringobe.roleplay.service.RoleplayRequestScopeService;
import com.neuringo.neuringobe.roleplay.service.RoleplayVoiceInputService;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@TestPropertySource(properties = "roleplay.retention.cleanup-enabled=false")
class RoleplayScopedVoiceTurnIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RoleplayRequestScopeService scopes;
    @Autowired RoleplayCheckpointStore store;
    @Autowired RoleplayRequestGuard guard;
    @Autowired RoleplayCanonicalRetentionService retention;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @Test
    void newVoiceRequestAssemblesProcessesAndCommitsBeforeReturningSuccess() {
        var f = fixture();
        var scope = scope(f);
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var upload = new Upload(scope.trace(request, turn));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            executor(workers, this::approved)
                                    .executeVoice(
                                            scope,
                                            request,
                                            turn,
                                            UUID.randomUUID(),
                                            upload,
                                            RoleplayTurnDeadline.start());
            assertThat(result.replayed()).isFalse();
            assertThat(result.checkpointVersion()).isEqualTo(1);
            assertThat(calls)
                    .containsExactly(
                            "READ",
                            "GUARD",
                            "LOOKUP",
                            "CONTEXT",
                            "STT",
                            "INPUT",
                            "CAUSE_ANALYSIS",
                            "RESPONSE_GENERATION",
                            "RESPONSE_EVALUATION",
                            "TTS",
                            "COMMIT",
                            "RELEASE",
                            "CLOSE");
            var response = (RoleplayTurnOutcome.SpokenResponse) result.outcome();
            assertThat(response.canonicalUtterance()).isEqualTo("친구가 울고 있어");
            assertThat(
                            jdbc.queryForObject(
                                    "select candidate_id from roleplay_turn where turn_id=?",
                                    UUID.class,
                                    turn))
                    .isEqualTo(response.response().candidateId());
            assertThat(
                            jdbc.queryForObject(
                                    "select response_text from roleplay_turn where turn_id=?",
                                    String.class,
                                    turn))
                    .isEqualTo(response.response().text());
            assertThat(
                            jdbc.queryForObject(
                                    "select last_turn_id from roleplay_session where session_id=?",
                                    UUID.class,
                                    f.session))
                    .isEqualTo(turn);
            assertThat(count(f)).isEqualTo(1);
            assertThat(upload.closed).isTrue();
            var publicResponse =
                    new RoleplayTurnResponseMapper((id, audio) -> Optional.empty()).map(result);
            assertThat(publicResponse.acceptedInput()).isEqualTo(response.canonicalUtterance());
            assertThat(publicResponse.messages().getFirst().text())
                    .isEqualTo(response.response().text());
            assertThat(JsonMapper.builder().build().writeValueAsString(publicResponse))
                    .doesNotContain(
                            "synthetic raw", "assessmentEligibility", "safeToSend", "candidateId");
        }
    }

    @Test
    void confirmedReplayAfterCompletionSkipsContextAudioAndProviders() {
        var f = fixture();
        var key = UUID.randomUUID();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, this::approved);
            var first =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            run(executor, scope(f), key, "a".repeat(64));
            retention.completeNormallyAndDelete(f.session, f.child, f.activity);
            var closed = scope(f);
            assertThat(closed.acceptsNewTurn()).isFalse();
            calls.clear();
            var replay =
                    (RoleplayCheckpointedTurnExecutor.Success)
                            run(executor, closed, key, "a".repeat(64));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.checkpointVersion()).isEqualTo(first.checkpointVersion());
            var prior = (RoleplayTurnOutcome.SpokenResponse) first.outcome();
            assertThat(replay.outcome()).isEqualTo(prior.response());
            var publicReplay =
                    new RoleplayTurnResponseMapper(
                                    (id, audio) -> {
                                        throw new AssertionError("Replay must not publish audio");
                                    })
                            .map(replay);
            assertThat(publicReplay.replayed()).isTrue();
            assertThat(publicReplay.acceptedInput()).isNull();
            assertThat(publicReplay.messages().getFirst().speech().available()).isFalse();
            assertThat(calls).containsExactly("READ", "GUARD", "LOOKUP", "RELEASE", "CLOSE");
            assertThat(count(f)).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select canonical_utterance from roleplay_turn where session_id=?",
                                    String.class,
                                    f.session))
                    .isNull();
        }
    }

    @Test
    void reusedKeyWithDifferentFingerprintSkipsAllNewWork() {
        var f = fixture();
        var key = UUID.randomUUID();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, this::approved);
            run(executor, scope(f), key, "a".repeat(64));
            calls.clear();
            assertThat(run(executor, scope(f), key, "b".repeat(64)))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Conflict.class);
            assertThat(calls).containsExactly("READ", "GUARD", "LOOKUP", "RELEASE", "CLOSE");
            assertThat(count(f)).isEqualTo(1);
        }
    }

    @Test
    void absentContextClosesUploadReleasesOwnershipAndAllowsRetryWithSameKey() {
        var f = fixture();
        var key = UUID.randomUUID();
        var scope = scope(f);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var unavailable =
                    executor(
                            workers,
                            k -> {
                                calls.add("CONTEXT");
                                return Optional.empty();
                            });
            assertThatThrownBy(() -> run(unavailable, scope, key, "a".repeat(64)))
                    .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
            assertThat(calls)
                    .containsExactly("READ", "GUARD", "LOOKUP", "CONTEXT", "RELEASE", "CLOSE");
            assertThat(count(f)).isZero();
            calls.clear();
            assertThat(run(executor(workers, this::approved), scope, key, "a".repeat(64)))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Success.class);
        }
    }

    @Test
    void staleCheckpointSkipsContextAndAudio() {
        var f = fixture();
        var stale = scope(f);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, this::approved);
            run(executor, stale, UUID.randomUUID(), "a".repeat(64));
            calls.clear();
            assertThat(run(executor, stale, UUID.randomUUID(), "a".repeat(64)))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Conflict.class);
            assertThat(calls).containsExactly("READ", "GUARD", "LOOKUP", "RELEASE", "CLOSE");
            assertThat(count(f)).isEqualTo(1);
        }
    }

    @Test
    void closedActivityDoesNotReachSourceOrSpeechForNewRequest() {
        var f = fixture();
        jdbc.update("update activity set status='COMPLETED' where activity_id=?", f.activity);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, this::approved);
            assertThatThrownBy(() -> run(executor, scope(f), UUID.randomUUID(), "a".repeat(64)))
                    .isInstanceOf(RoleplayContextAssembler.Unavailable.class);
            assertThat(calls).containsExactly("READ", "GUARD", "LOOKUP", "RELEASE", "CLOSE");
            assertThat(count(f)).isZero();
        }
    }

    @Test
    void uploadFromAnotherTraceFailsBeforeSttAndCommit() {
        var f = fixture();
        var scope = scope(f);
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var upload = new Upload(scope.trace(request, UUID.randomUUID()));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, this::approved);
            assertThatThrownBy(
                            () ->
                                    executor.executeVoice(
                                            scope,
                                            request,
                                            turn,
                                            UUID.randomUUID(),
                                            upload,
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).containsExactly("READ", "CLOSE");
            assertThat(upload.closed).isTrue();
            assertThat(count(f)).isZero();
        }
    }

    @Test
    void simultaneousDuplicateDuringContextLookupDoesNotStartSecondContextOrSpeech()
            throws Exception {
        var f = fixture();
        var scope = scope(f);
        var key = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        RoleplayContextSource source =
                k -> {
                    reads.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("context wait timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return approved(k);
                };
        try (var workers = Executors.newFixedThreadPool(2);
                var callers = Executors.newSingleThreadExecutor()) {
            var executor = executor(workers, source);
            var first = callers.submit(() -> run(executor, scope, key, "a".repeat(64)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(run(executor, scope, key, "a".repeat(64)))
                        .isInstanceOf(RoleplayCheckpointedTurnExecutor.Processing.class);
                assertThat(reads).hasValue(1);
                assertThat(calls).doesNotContain("STT", "COMMIT");
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(RoleplayCheckpointedTurnExecutor.Success.class);
            assertThat(reads).hasValue(1);
            assertThat(count(f)).isEqualTo(1);
            assertThat(calls.stream().filter("STT"::equals).count()).isEqualTo(1);
        }
    }

    @Test
    void cancellationDuringInputPreparationNeverAcquiresGuardOrWritesTurn() {
        var f = fixture();
        var scope = scope(f);
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var deadline = RoleplayTurnDeadline.start();
        var upload = new Upload(scope.trace(request, turn));
        RoleplayAudioResource cancelling =
                new RoleplayAudioResource() {
                    public SpeechTranscriptionRequest request() {
                        var captured = upload.request();
                        deadline.cancel();
                        return captured;
                    }

                    public void close() {
                        upload.close();
                    }
                };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result =
                    executor(workers, this::approved)
                            .executeVoice(
                                    scope, request, turn, UUID.randomUUID(), cancelling, deadline);
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Recovery.class);
            assertThat(calls).containsExactly("READ", "CLOSE");
            assertThat(upload.closed).isTrue();
            assertThat(count(f)).isZero();
        }
    }

    @TempDir Path uploadDirectory;

    @Test
    void serverCreatedSpoolFlowsToCheckpointAndIsDeletedAfterResponse() throws Exception {
        var f = fixture();
        var scope = scope(f);
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var factory =
                new RoleplayUploadSpoolFactory(
                        uploadDirectory, new RoleplayUploadPolicy(16, Set.of(AudioFormat.WEBM)));
        var deadline = RoleplayTurnDeadline.start();
        var audio =
                factory.create(
                        new ByteArrayInputStream(new byte[] {1, 2, 3}),
                        "audio/webm",
                        scope.trace(request, turn),
                        deadline);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result =
                    executor(workers, this::approved)
                            .executeVoice(scope, request, turn, UUID.randomUUID(), audio, deadline);
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Success.class);
            assertThat(count(f)).isEqualTo(1);
            try (var files = Files.list(uploadDirectory)) {
                assertThat(files.count()).isZero();
            }
        }
    }

    @Test
    void voiceEntryConnectsAuthenticatedScopeUploadCommitAndPublicResponse() throws Exception {
        var f = fixture();
        var source = new Input(new byte[] {1, 2, 3});
        try (var workers = Executors.newSingleThreadExecutor()) {
            var dto =
                    service(
                                    workers,
                                    new RoleplayTurnResponseMapper(
                                            (id, speech) -> Optional.empty()))
                            .submitVoice(
                                    auth(f),
                                    f.activity,
                                    f.session,
                                    UUID.randomUUID(),
                                    source,
                                    "audio/webm",
                                    RoleplayTurnDeadline.start());
            assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.DELIVERED);
            assertThat(dto.acceptedInput()).isEqualTo("친구가 울고 있어");
            assertThat(dto.checkpointVersion()).isEqualTo(1);
            assertThat(source.closes).isEqualTo(1);
            assertThat(count(f)).isEqualTo(1);
            assertSpoolEmpty();
        }
    }

    @Test
    void unauthenticatedOrForeignScopeClosesSourceWithoutReadingOrExecuting() throws Exception {
        var own = fixture();
        var other = fixture();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var service =
                    service(
                            workers,
                            new RoleplayTurnResponseMapper((id, speech) -> Optional.empty()));
            var missing = new Input(new byte[] {1});
            assertThatThrownBy(
                            () ->
                                    service.submitVoice(
                                            null,
                                            own.activity,
                                            own.session,
                                            UUID.randomUUID(),
                                            missing,
                                            "audio/webm",
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(com.neuringo.neuringobe.common.ApiException.class);
            var foreign = new Input(new byte[] {1});
            assertThatThrownBy(
                            () ->
                                    service.submitVoice(
                                            auth(own),
                                            other.activity,
                                            other.session,
                                            UUID.randomUUID(),
                                            foreign,
                                            "audio/webm",
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(com.neuringo.neuringobe.common.ResourceNotFoundException.class);
            for (var source : List.of(missing, foreign)) {
                assertThat(source.reads).isZero();
                assertThat(source.closes).isEqualTo(1);
            }
            assertThat(calls).isEmpty();
            assertThat(count(own)).isZero();
            assertSpoolEmpty();
        }
    }

    @Test
    void entryRejectsOversizedUploadBeforeContextOrAiAndCleansSource() throws Exception {
        var f = fixture();
        var source = new Input(new byte[17]);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var service =
                    service(
                            workers,
                            new RoleplayTurnResponseMapper((id, speech) -> Optional.empty()));
            assertThatThrownBy(
                            () ->
                                    service.submitVoice(
                                            auth(f),
                                            f.activity,
                                            f.session,
                                            UUID.randomUUID(),
                                            source,
                                            "audio/webm",
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(RoleplayUploadSpoolFactory.Rejected.class);
            assertThat(source.closes).isEqualTo(1);
            assertThat(calls).isEmpty();
            assertThat(count(f)).isZero();
            assertSpoolEmpty();
        }
    }

    @Test
    void entryReplayAndChangedBytesUseServerFingerprintWithNoAdditionalAi() throws Exception {
        var f = fixture();
        var key = UUID.randomUUID();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var service =
                    service(
                            workers,
                            new RoleplayTurnResponseMapper((id, speech) -> Optional.empty()));
            var first =
                    service.submitVoice(
                            auth(f),
                            f.activity,
                            f.session,
                            key,
                            new Input(new byte[] {1, 2, 3}),
                            "audio/webm",
                            RoleplayTurnDeadline.start());
            calls.clear();
            var replay =
                    service.submitVoice(
                            auth(f),
                            f.activity,
                            f.session,
                            key,
                            new Input(new byte[] {1, 2, 3}),
                            "video/webm",
                            RoleplayTurnDeadline.start());
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.acceptedInput()).isNull();
            assertThat(replay.messages()).isEqualTo(first.messages());
            var conflict =
                    service.submitVoice(
                            auth(f),
                            f.activity,
                            f.session,
                            key,
                            new Input(new byte[] {4, 5, 6}),
                            "audio/webm",
                            RoleplayTurnDeadline.start());
            assertThat(conflict.status()).isEqualTo(RoleplayDeliveryStatus.CONFLICT);
            assertThat(calls).doesNotContain("CONTEXT", "STT", "TTS", "COMMIT");
            assertThat(count(f)).isEqualTo(1);
            assertSpoolEmpty();
        }
    }

    @Test
    void entryPreflightTimeoutReturnsApprovedNoticeAndClosesUnreadStream() throws Exception {
        var f = fixture();
        var source = new Input(new byte[] {1});
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var dto =
                    service(
                                    workers,
                                    new RoleplayTurnResponseMapper(
                                            (id, speech) -> Optional.empty()))
                            .submitVoice(
                                    auth(f),
                                    f.activity,
                                    f.session,
                                    UUID.randomUUID(),
                                    source,
                                    "audio/webm",
                                    deadline);
            assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.REINPUT_REQUIRED);
            assertThat(dto.messages().getFirst().text()).isEqualTo("다시 한 번만 말해줄래?");
            assertThat(source.reads).isZero();
            assertThat(source.closes).isEqualTo(1);
            assertThat(calls).isEmpty();
            assertSpoolEmpty();
        }
    }

    @Test
    void responsePublicationErrorLeavesCommittedReplayAvailableAndNoTemporaryFile()
            throws Exception {
        var f = fixture();
        var key = UUID.randomUUID();
        var source = new Input(new byte[] {1, 2, 3});
        try (var workers = Executors.newSingleThreadExecutor()) {
            var failing =
                    service(
                            workers,
                            new RoleplayTurnResponseMapper(
                                    (id, speech) -> {
                                        throw new IllegalStateException(
                                                "synthetic publication failure");
                                    }));
            assertThatThrownBy(
                            () ->
                                    failing.submitVoice(
                                            auth(f),
                                            f.activity,
                                            f.session,
                                            key,
                                            source,
                                            "audio/webm",
                                            RoleplayTurnDeadline.start()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic publication failure");
            assertThat(source.closes).isEqualTo(1);
            assertThat(count(f)).isEqualTo(1);
            assertSpoolEmpty();
            calls.clear();
            var replay =
                    service(
                                    workers,
                                    new RoleplayTurnResponseMapper(
                                            (id, speech) -> {
                                                throw new AssertionError(
                                                        "No publication on replay");
                                            }))
                            .submitVoice(
                                    auth(f),
                                    f.activity,
                                    f.session,
                                    key,
                                    new Input(new byte[] {1, 2, 3}),
                                    "audio/webm",
                                    RoleplayTurnDeadline.start());
            assertThat(replay.replayed()).isTrue();
            assertThat(calls).doesNotContain("CONTEXT", "STT", "TTS", "COMMIT");
            assertSpoolEmpty();
        }
    }

    @Test
    void entryTimeoutDoesNotHideInputCleanupFailure() throws Exception {
        var f = fixture();
        var deadline = RoleplayTurnDeadline.start();
        deadline.cancel();
        var source =
                new Input(new byte[] {1}) {
                    @Override
                    public void close() {
                        super.close();
                        throw new IllegalStateException("synthetic cleanup failure");
                    }
                };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var service =
                    service(
                            workers,
                            new RoleplayTurnResponseMapper((id, speech) -> Optional.empty()));
            assertThatThrownBy(
                            () ->
                                    service.submitVoice(
                                            auth(f),
                                            f.activity,
                                            f.session,
                                            UUID.randomUUID(),
                                            source,
                                            "audio/webm",
                                            deadline))
                    .isInstanceOfSatisfying(
                            RoleplayTurnDeadline.Expired.class,
                            error -> assertThat(error.getSuppressed()).hasSize(1));
            assertThat(source.closes).isEqualTo(1);
            assertThat(source.reads).isZero();
            assertSpoolEmpty();
        }
    }

    private RoleplayVoiceInputService service(
            java.util.concurrent.ExecutorService workers, RoleplayTurnResponseMapper mapper) {
        return new RoleplayVoiceInputService(
                scopes,
                new RoleplayUploadSpoolFactory(
                        uploadDirectory, new RoleplayUploadPolicy(16, Set.of(AudioFormat.WEBM))),
                executor(workers, this::approved),
                mapper,
                new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())));
    }

    private Authentication auth(Fixture f) {
        return new UsernamePasswordAuthenticationToken(
                new ChildPrincipal(f.child),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CHILD")));
    }

    private void assertSpoolEmpty() throws Exception {
        try (var files = Files.list(uploadDirectory)) {
            assertThat(files.count()).isZero();
        }
    }

    private static class Input extends ByteArrayInputStream {
        int reads;
        int closes;

        private Input(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] buffer, int off, int len) {
            reads++;
            return super.read(buffer, off, len);
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private RoleplayRequestScopeService.Scope scope(Fixture f) {
        return scopes.load(f.child, f.activity, f.session);
    }

    private long count(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from roleplay_turn where session_id=?", Long.class, f.session);
    }

    private RoleplayCheckpointedTurnExecutor.Result run(
            RoleplayScopedVoiceTurnExecutor executor,
            RoleplayRequestScopeService.Scope scope,
            UUID key,
            String fingerprint) {
        var request = UUID.randomUUID();
        var turn = UUID.randomUUID();
        return executor.executeVoice(
                scope,
                request,
                turn,
                key,
                new Upload(
                        scope.trace(request, turn),
                        fingerprint.startsWith("b") ? new byte[] {4, 5, 6} : new byte[] {1, 2, 3}),
                RoleplayTurnDeadline.start());
    }

    private Optional<RoleplayContextSource.Snapshot> approved(RoleplayContextSource.Key key) {
        calls.add("CONTEXT");
        var fixture = RoleplayFixtures.input("");
        return Optional.of(
                new RoleplayContextSource.Snapshot(
                        key,
                        "synthetic approval",
                        fixture.scenario(),
                        new RoleplayTurnInput.State(
                                RoleplayFixtures.MG_EMOTION, "S1", 1, 0, false, 1, 77, 9),
                        fixture.recentDialogue()));
    }

    private RoleplayScopedVoiceTurnExecutor executor(
            java.util.concurrent.ExecutorService workers, RoleplayContextSource source) {
        var recordingStore =
                new RoleplayCheckpointStore() {
                    public LookupResult findConfirmed(
                            RoleplayRequestIdentity id, RoleplayTurnDeadline budget) {
                        calls.add("LOOKUP");
                        return store.findConfirmed(id, budget);
                    }

                    public CommitResult commit(
                            RoleplayCheckpointCommand command, RoleplayTurnDeadline budget) {
                        calls.add("COMMIT");
                        return store.commit(command, budget);
                    }
                };
        RoleplayRequestGuard recordingGuard =
                (id, budget) -> {
                    calls.add("GUARD");
                    return guard.tryAcquire(id, budget)
                            .map(
                                    lease ->
                                            () -> {
                                                try {
                                                    lease.close();
                                                } finally {
                                                    calls.add("RELEASE");
                                                }
                                            });
                };
        var checkpoints =
                new RoleplayCheckpointedTurnExecutor(
                        new RoleplayTurnRunner(workers),
                        recordingStore,
                        new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                        recordingGuard);
        var mapper = JsonMapper.builder().build();
        var speech =
                new RoleplaySpeechTurnPipeline(
                        request -> {
                            calls.add("STT");
                            assertThat(request.audio())
                                    .containsExactly((byte) 1, (byte) 2, (byte) 3);
                            return success(
                                    new SpeechTranscription(
                                            "synthetic raw", 0.9, "fake", "fake", null, null),
                                    request.traceContext(),
                                    AiOperation.SPEECH_TRANSCRIPTION);
                        },
                        request -> {
                            calls.add("TTS");
                            return success(
                                    new SynthesizedSpeech(
                                            new byte[] {7, 8},
                                            AudioFormat.MP3,
                                            "fake",
                                            "fake",
                                            null),
                                    request.traceContext(),
                                    AiOperation.SPEECH_SYNTHESIS);
                        },
                        text -> {
                            calls.add("INPUT");
                            return new RoleplayInputProcessor.Accepted("친구가 울고 있어");
                        },
                        new RoleplayPromptFactory(mapper, null),
                        new StructuredLlmExecutor(
                                request -> {
                                    calls.add(request.operation().name());
                                    String json =
                                            switch (request.operation()) {
                                                case CAUSE_ANALYSIS ->
                                                        RoleplayFixtures.analysisJson(
                                                                "PROBE_EMOTION",
                                                                RoleplayFixtures.MG_EMOTION,
                                                                "S1");
                                                case RESPONSE_GENERATION ->
                                                        RoleplayFixtures.candidateJson(
                                                                "친구는 어떤 기분일까?", "GUIDING_QUESTION");
                                                case RESPONSE_EVALUATION ->
                                                        RoleplayFixtures.evaluationJson(
                                                                "PASS", null, true, 0, "[]",
                                                                "null");
                                                default ->
                                                        throw new AssertionError(
                                                                "Unexpected operation");
                                            };
                                    json =
                                            json.replace(
                                                    RoleplayFixtures.TURN_ID.toString(),
                                                    request.traceContext().turnId().toString());
                                    if (request.traceContext().candidateId() != null)
                                        json =
                                                json.replace(
                                                        RoleplayFixtures.CANDIDATE_ID.toString(),
                                                        request.traceContext()
                                                                .candidateId()
                                                                .toString());
                                    return success(
                                            new LlmCompletion(
                                                    json, "fake", "fake", "stop", null, null),
                                            request.traceContext(),
                                            request.operation());
                                }));
        return new RoleplayScopedVoiceTurnExecutor(
                checkpoints, new RoleplayContextAssembler(source), speech);
    }

    private <T> AiCallResult<T> success(T data, AiTraceContext trace, AiOperation op) {
        return new AiCallResult.Success<>(
                data,
                new AiCallMetadata(
                        trace.requestId(),
                        op,
                        "fake",
                        "fake",
                        "test/v1",
                        "v1",
                        null,
                        0,
                        1,
                        null,
                        null,
                        "stop"));
    }

    private final class Upload implements RoleplayAudioResource {
        private final AiTraceContext trace;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final byte[] bytes;

        private Upload(AiTraceContext trace) {
            this(trace, new byte[] {1, 2, 3});
        }

        private Upload(AiTraceContext trace, byte[] bytes) {
            this.trace = trace;
            this.bytes = bytes.clone();
        }

        public SpeechTranscriptionRequest request() {
            calls.add("READ");
            if (closed.get()) throw new IllegalStateException("Upload already closed");
            return new SpeechTranscriptionRequest(trace, 1, bytes, AudioFormat.WEBM);
        }

        public void close() {
            if (!closed.compareAndSet(false, true)) throw new AssertionError("Upload closed twice");
            calls.add("CLOSE");
        }
    }

    private record Fixture(UUID session, UUID activity, UUID child, UUID scenario) {}

    private Fixture fixture() {
        UUID user = UUID.randomUUID(),
                classroom = UUID.randomUUID(),
                child = UUID.randomUUID(),
                goal = UUID.randomUUID();
        UUID activity = UUID.randomUUID(),
                session = UUID.randomUUID(),
                scenario = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into user_account(user_id,email,password_hash,name,role,status,created_at) values(?,?,?,'synthetic','INSTRUCTOR','ACTIVE',?)",
                user,
                user + "@example.com",
                "test-hash",
                now);
        jdbc.update(
                "insert into classroom(class_id,instructor_id,name,status) values(?,?,'synthetic','ACTIVE')",
                classroom,
                user);
        jdbc.update(
                "insert into child(child_id,class_id,display_name,status) values(?,?,'synthetic','ACTIVE')",
                child,
                classroom);
        jdbc.update(
                "insert into learning_goal(goal_id,child_id,instructor_id,title,content_hash,created_at) values(?,?,?,'synthetic',?,?)",
                goal,
                child,
                user,
                "a".repeat(64),
                now);
        jdbc.update(
                "insert into activity(activity_id,child_id,goal_id,scenario_id,status,assigned_at) values(?,?,?,?,'IN_PROGRESS',?)",
                activity,
                child,
                goal,
                scenario,
                now);
        jdbc.update(
                "insert into roleplay_session(session_id,activity_id,child_id,scenario_id,scenario_version,status,last_activity_at) values(?,?,?,?,1,'IN_PROGRESS',?)",
                session,
                activity,
                child,
                scenario,
                now);
        return new Fixture(session, activity, child, scenario);
    }
}
