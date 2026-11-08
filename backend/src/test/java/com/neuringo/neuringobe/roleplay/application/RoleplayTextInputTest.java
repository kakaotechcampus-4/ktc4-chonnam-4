package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTextInputRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RoleplayTextInputTest {
    @Test
    void fingerprintIsStableButDistinguishesExactTextIncludingWhitespaceAndSurrogates() {
        var deadline = RoleplayTurnDeadline.start();
        assertThat(RoleplayTextInput.fingerprint("same", deadline))
                .hasSize(64)
                .isEqualTo(RoleplayTextInput.fingerprint("same", deadline))
                .isNotEqualTo(RoleplayTextInput.fingerprint("same ", deadline));
        assertThat(RoleplayTextInput.fingerprint("\uD800", deadline))
                .isNotEqualTo(RoleplayTextInput.fingerprint("\uDC00", deadline));
        assertThat(new RoleplayTextInputRequest("private-input").toString())
                .doesNotContain("private-input");
        deadline.cancel();
        assertThatThrownBy(() -> RoleplayTextInput.fingerprint("same", deadline))
                .isInstanceOf(RoleplayTurnDeadline.Expired.class);
    }

    @Test
    void rejectedTextCannotCallTheVoiceFailureWriterEvenForACountableReason() {
        var store = mock(RoleplayCheckpointStore.class);
        var failures = mock(RoleplayInputFailureStore.class);
        var guard = mock(RoleplayRequestGuard.class);
        var lease = mock(RoleplayRequestGuard.Lease.class);
        when(guard.tryAcquire(any(), any())).thenReturn(Optional.of(lease));
        when(failures.findRecorded(any(), any()))
                .thenReturn(new RoleplayInputFailureStore.Absent());
        when(store.findConfirmed(any(), any())).thenReturn(new RoleplayCheckpointStore.Absent());
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null);
        var identity = new RoleplayRequestIdentity(trace, UUID.randomUUID(), "a".repeat(64), 0, 1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var executor =
                    new RoleplayCheckpointedTurnExecutor(
                            new RoleplayTurnRunner(workers),
                            store,
                            new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of())),
                            guard,
                            failures);
            var result =
                    executor.executePrepared(
                            RoleplayTurnDeadline.start(),
                            deadline ->
                                    new RoleplayCheckpointedTurnExecutor.PreparedExecution(
                                            identity,
                                            active ->
                                                    new RoleplayTurnResult.InputRejected(
                                                            RoleplayTurnResult.InputFailure
                                                                    .LOW_CONFIDENCE),
                                            false));
            assertThat(result).isInstanceOf(RoleplayCheckpointedTurnExecutor.Recovery.class);
        }
        verify(failures).findRecorded(any(), any());
        verifyNoMoreInteractions(failures);
        verify(store).findConfirmed(any(), any());
        verifyNoMoreInteractions(store);
    }
}
