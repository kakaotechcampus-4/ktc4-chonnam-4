package com.neuringo.neuringobe.ai.application.roleplay;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Trusted configuration only. Approval is performed outside this class, never by an LLM. */
public final class RoleplayNoticeCatalog {
    public enum Kind {
        TIMEOUT,
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

    /** Only DEC-010 has a confirmed literal in the current specification. */
    public RoleplayNoticeCatalog(Collection<ApprovedNotice> approvedNotices) {
        var entries = new EnumMap<Kind, ApprovedNotice>(Kind.class);
        entries.put(
                Kind.TIMEOUT,
                new ApprovedNotice(
                        Kind.TIMEOUT,
                        new RoleplayTurnResult.TimedOut().message(),
                        "DEC-010",
                        "DEC-010"));
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
