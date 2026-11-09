package com.neuringo.neuringobe.ai.application.roleplay;

/** 판정 가능 여부와 관계없이 저장된 역할극 입력의 안전 상한이다. */
public final class RoleplayStoredTurnSafetyLimit {
    public static final int MAXIMUM_STORED_TURNS = 10;

    private RoleplayStoredTurnSafetyLimit() {}

    public static boolean reached(int storedTurnNumber) {
        return storedTurnNumber >= MAXIMUM_STORED_TURNS;
    }
}
