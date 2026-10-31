package com.neuringo.neuringobe.roleplay.dto;

import java.util.UUID;

/** 원문 발화를 읽지 않고 마지막 확정 서버 응답만 복원한다. */
public record CommittedRoleplayMessage(UUID messageId, String text) {
    @Override
    public String toString() {
        return "CommittedRoleplayMessage[content=redacted]";
    }
}
