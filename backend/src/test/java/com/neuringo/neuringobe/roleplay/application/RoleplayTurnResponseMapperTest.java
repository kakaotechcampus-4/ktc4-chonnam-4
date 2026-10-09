package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.model.SynthesizedSpeech;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayCheckpointedTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayNoticeCatalog;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcome;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnOutcomeResolver;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryAction;
import com.neuringo.neuringobe.roleplay.model.RoleplayDeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class RoleplayTurnResponseMapperTest {
    private final UUID candidate = UUID.randomUUID();
    private final RoleplayTurnOutcome.ApprovedResponse approved =
            new RoleplayTurnOutcome.ApprovedResponse(
                    candidate, "synthetic approved child response");
    private final AtomicInteger publications = new AtomicInteger();
    private final RoleplayTurnResponseMapper mapper =
            new RoleplayTurnResponseMapper(
                    (id, speech) -> {
                        publications.incrementAndGet();
                        assertThat(id).isEqualTo(candidate);
                        return Optional.of("/synthetic-protected-speech/" + id);
                    });

    @Test
    void approvedSpokenSuccessExposesCanonicalInputTextAndPlayableResourceOnly() {
        var dto = mapper.map(success(spoken(audio()), false));
        assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.DELIVERED);
        assertThat(dto.action()).isEqualTo(RoleplayDeliveryAction.NONE);
        assertThat(dto.acceptedInput()).isEqualTo("synthetic canonical input");
        assertThat(dto.checkpointVersion()).isEqualTo(4);
        assertThat(dto.messages().getFirst().text()).isEqualTo(approved.text());
        assertThat(dto.messages().getFirst().speech().available()).isTrue();
        assertThat(dto.messages().getFirst().speech().url())
                .isEqualTo("/synthetic-protected-speech/" + candidate);
        var tree = JsonMapper.builder().build().readTree(json(dto));
        assertThat(tree.properties().stream().map(entry -> entry.getKey()).toList())
                .containsExactlyInAnyOrder(
                        "status",
                        "action",
                        "messages",
                        "acceptedInput",
                        "checkpointVersion",
                        "replayed");
        assertThat(json(dto))
                .doesNotContain(
                        "secret-vendor",
                        "secret-model",
                        "secret-voice",
                        "assessmentEligibility",
                        "retention",
                        "candidateId",
                        "evaluation",
                        "audio",
                        "approvalReference");
        assertThat(publications).hasValue(1);
    }

    @Test
    void ttsFailureStillReturnsCommittedTextAndCanonicalInputWithoutAudioPublication() {
        var dto = mapper.map(success(spoken(null), false));
        assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.DELIVERED);
        assertThat(dto.messages().getFirst().speech())
                .isEqualTo(new RoleplayTurnResponse.Speech(false, null));
        assertThat(dto.acceptedInput()).isEqualTo("synthetic canonical input");
        assertThat(publications).hasValue(0);
    }

    @Test
    void unavailableSpeechDeliveryKeepsTextWithoutInventingAUrl() {
        var unavailable = new RoleplayTurnResponseMapper((id, speech) -> Optional.empty());
        var dto = unavailable.map(success(spoken(audio()), false));
        assertThat(dto.messages().getFirst().text()).isEqualTo(approved.text());
        assertThat(dto.messages().getFirst().speech())
                .isEqualTo(new RoleplayTurnResponse.Speech(false, null));
    }

    @Test
    void replayDoesNotExposeCanonicalInputOrRegenerateAudio() {
        var dto = mapper.map(success(approved, true));
        assertThat(dto.replayed()).isTrue();
        assertThat(dto.acceptedInput()).isNull();
        assertThat(dto.messages().getFirst().text()).isEqualTo(approved.text());
        assertThat(dto.messages().getFirst().speech().available()).isFalse();
        assertThat(publications).hasValue(0);
    }

    @Test
    void timeoutUsesConfirmedLiteralAndRequestsNewInputWithoutCheckpoint() {
        var outcome =
                new RoleplayTurnOutcomeResolver(new RoleplayNoticeCatalog(List.of()))
                        .resolve(new RoleplayTurnResult.TimedOut());
        var dto = mapper.map(new RoleplayCheckpointedTurnExecutor.Recovery(outcome));
        assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.REINPUT_REQUIRED);
        assertThat(dto.action()).isEqualTo(RoleplayDeliveryAction.REQUEST_NEW_INPUT);
        assertThat(dto.messages().getFirst().text()).isEqualTo("다시 한 번만 말해줄래?");
        assertNoLearningResult(dto);
    }

    @Test
    void safetyStopIncludesChildNoticeAndNeverTeacherOrApprovalDetails() {
        var child =
                notice(
                        RoleplayNoticeCatalog.Kind.SAFETY_ESCALATION_CHILD,
                        "synthetic child notice");
        var teacher =
                notice(
                        RoleplayNoticeCatalog.Kind.SAFETY_ESCALATION_TEACHER,
                        "secret teacher notice");
        var dto =
                mapper.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(
                                new RoleplayTurnOutcome.SafetyStop(child, teacher)));
        assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.STOPPED);
        assertThat(dto.action()).isEqualTo(RoleplayDeliveryAction.STOP_DIALOGUE);
        assertThat(dto.messages().getFirst().text()).isEqualTo(child.text());
        assertThat(json(dto)).doesNotContain(teacher.text(), "secret approval", "secret-version");
        assertNoLearningResult(dto);
    }

    @ParameterizedTest
    @EnumSource(RoleplayTurnOutcome.Action.class)
    void missingNoticePreservesActionWithoutInventingText(RoleplayTurnOutcome.Action action) {
        var dto =
                mapper.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(
                                new RoleplayTurnOutcome.NoticeUnavailable(action)));
        assertThat(dto.status()).isEqualTo(RoleplayDeliveryStatus.NOTICE_UNAVAILABLE);
        assertThat(dto.action().name()).isEqualTo(action.name());
        assertThat(dto.messages()).isEmpty();
        assertNoLearningResult(dto);
    }

    @Test
    void missingInputNoticeStillRequestsNewInput() {
        var dto =
                mapper.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(
                                new RoleplayTurnOutcome.InputNoticeUnavailable()));
        assertThat(dto.action()).isEqualTo(RoleplayDeliveryAction.REQUEST_NEW_INPUT);
        assertThat(dto.messages()).isEmpty();
        assertNoLearningResult(dto);
    }

    @Test
    void approvedInputRetryAndEvaluationErrorCarryOnlyTheirApprovedNotice() {
        var retry =
                mapper.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(
                                new RoleplayTurnOutcome.InputRetry(
                                        notice(
                                                RoleplayNoticeCatalog.Kind.INPUT_QUALITY,
                                                "synthetic input notice"))));
        assertThat(retry.status()).isEqualTo(RoleplayDeliveryStatus.REINPUT_REQUIRED);
        var error =
                mapper.map(
                        new RoleplayCheckpointedTurnExecutor.Recovery(
                                new RoleplayTurnOutcome.Guidance(
                                        notice(
                                                RoleplayNoticeCatalog.Kind.SAFETY_CHECK_ERROR,
                                                "synthetic error notice"),
                                        RoleplayTurnOutcome.Action.RETRY_TURN)));
        assertThat(error.status()).isEqualTo(RoleplayDeliveryStatus.RETRY_REQUIRED);
        assertThat(error.action()).isEqualTo(RoleplayDeliveryAction.RETRY_TURN);
        assertNoLearningResult(retry);
        assertNoLearningResult(error);
    }

    @Test
    void conflictProcessingAndCapacityNeverLookLikeDeliveredResponses() {
        var conflict = mapper.map(new RoleplayCheckpointedTurnExecutor.Conflict());
        var processing = mapper.map(new RoleplayCheckpointedTurnExecutor.Processing());
        var busy = mapper.map(new RoleplayCheckpointedTurnExecutor.CapacityUnavailable());
        assertThat(conflict.status()).isEqualTo(RoleplayDeliveryStatus.CONFLICT);
        assertThat(processing.status()).isEqualTo(RoleplayDeliveryStatus.PROCESSING);
        assertThat(processing.action()).isEqualTo(RoleplayDeliveryAction.WAIT);
        assertThat(busy.status()).isEqualTo(RoleplayDeliveryStatus.BUSY);
        assertThat(busy.action()).isEqualTo(RoleplayDeliveryAction.RESUBMIT_SAME_REQUEST);
        for (var dto : List.of(conflict, processing, busy)) {
            assertThat(dto.messages()).isEmpty();
            assertNoLearningResult(dto);
        }
    }

    @Test
    void inconsistentInternalResultsCannotBeSerializedAsDelivery() {
        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        success(
                                                new RoleplayTurnOutcome.InputNoticeUnavailable(),
                                                false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> mapper.map(new RoleplayCheckpointedTurnExecutor.Recovery(approved)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(success(spoken(audio()), true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        new RoleplayCheckpointedTurnExecutor.Success(
                                                approved, 0, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(publications).hasValue(0);
    }

    @Test
    void deliveryAndSpeechLogsRedactContentAndResourceAddress() {
        var dto = mapper.map(success(spoken(audio()), false));
        assertThat(dto.toString()).doesNotContain(approved.text(), dto.acceptedInput());
        assertThat(dto.messages().getFirst().toString()).doesNotContain(approved.text());
        assertThat(dto.messages().getFirst().speech().toString())
                .doesNotContain("synthetic-protected");
    }

    private void assertNoLearningResult(RoleplayTurnResponse dto) {
        assertThat(dto.acceptedInput()).isNull();
        assertThat(dto.checkpointVersion()).isNull();
        assertThat(dto.replayed()).isFalse();
        assertThat(publications).hasValue(0);
    }

    private RoleplayNoticeCatalog.ApprovedNotice notice(
            RoleplayNoticeCatalog.Kind kind, String text) {
        return new RoleplayNoticeCatalog.ApprovedNotice(
                kind, text, "secret-version", "secret approval");
    }

    private RoleplayTurnOutcome.SpokenResponse spoken(SynthesizedSpeech audio) {
        return new RoleplayTurnOutcome.SpokenResponse(approved, "synthetic canonical input", audio);
    }

    private SynthesizedSpeech audio() {
        return new SynthesizedSpeech(
                new byte[] {7, 8},
                AudioFormat.MP3,
                "secret-vendor",
                "secret-model",
                "secret-voice");
    }

    private RoleplayCheckpointedTurnExecutor.Success success(
            RoleplayTurnOutcome outcome, boolean replayed) {
        return new RoleplayCheckpointedTurnExecutor.Success(outcome, 4, replayed);
    }

    private String json(RoleplayTurnResponse dto) {
        return JsonMapper.builder().build().writeValueAsString(dto);
    }
}
