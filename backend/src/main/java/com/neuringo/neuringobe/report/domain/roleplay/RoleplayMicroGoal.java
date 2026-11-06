package com.neuringo.neuringobe.report.domain.roleplay;

import java.util.Objects;
import java.util.UUID;

/** 승인된 시나리오의 작은 목표. title 은 강사 화면과 리포트에 보일 짧은 이름이다. */
public record RoleplayMicroGoal(UUID id, String title) {

    public RoleplayMicroGoal {
        Objects.requireNonNull(id, "id must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }
}
