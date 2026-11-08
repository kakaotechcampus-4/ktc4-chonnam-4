package com.neuringo.neuringobe.roleplay.dto;

import jakarta.validation.constraints.NotNull;

public record RoleplayTextInputRequest(@NotNull String text) {
    @Override
    public String toString() {
        return "RoleplayTextInputRequest[content=redacted]";
    }
}
