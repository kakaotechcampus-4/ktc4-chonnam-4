package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Trusted configuration only. Approval is performed outside this class, never by an LLM. */
public final class RoleplayNoticeCatalog {
    public static final String REINPUT_TEXT = "다시 한 번만 말해줄래?";

    public enum Kind {
        TIMEOUT,
        INPUT_RECOVERY,
        INPUT_QUALITY,
        INPUT_CONFIRMATION,
        SAFE_FALLBACK,
        SAFETY_CHECK_ERROR,
        SAFETY_ESCALATION_CHILD,
        SAFETY_ESCALATION_TEACHER
    }

    public record ApprovedNotice(Kind kind, String text, String version, String approvalReference) {
        public ApprovedNotice {
            Objects.requireNonNull(kind);
            requireText(text);
            requireText(version);
            requireText(approvalReference);
        }

        private static void requireText(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Notice and approval metadata must not be blank");
            }
        }
    }

    private final Map<Kind, ApprovedNotice> notices;

    /** 확정된 재입력 문구를 시간 초과와 파일 검증 복구에 재사용한다. */
    public RoleplayNoticeCatalog(Collection<ApprovedNotice> approvedNotices) {
        var entries = new EnumMap<Kind, ApprovedNotice>(Kind.class);
        entries.put(
                Kind.TIMEOUT,
                new ApprovedNotice(
                        Kind.TIMEOUT,
                        new RoleplayTurnResult.TimedOut().message(),
                        "DEC-010",
                        "DEC-010"));
        entries.put(
                Kind.INPUT_RECOVERY,
                new ApprovedNotice(
                        Kind.INPUT_RECOVERY,
                        REINPUT_TEXT,
                        "INPUT-RECOVERY-V1",
                        "USER-2026-10-30-VOICE-REINPUT"));
        for (ApprovedNotice notice : Objects.requireNonNull(approvedNotices)) {
            Objects.requireNonNull(notice);
            if (entries.putIfAbsent(notice.kind(), notice) != null) {
                throw new IllegalArgumentException("Duplicate or reserved notice kind");
            }
        }
        notices = Map.copyOf(entries);
    }

    public Optional<ApprovedNotice> find(Kind kind) {
        return Optional.ofNullable(notices.get(Objects.requireNonNull(kind)));
    }
}
