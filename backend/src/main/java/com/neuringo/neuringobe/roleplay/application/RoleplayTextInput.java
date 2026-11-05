package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 정제 전 문자열의 UTF-16 코드 단위와 입력 종류를 포함한 지문이며 원문을 저장하지 않는다. */
public final class RoleplayTextInput {
    private RoleplayTextInput() {}

    public static String fingerprint(String text, RoleplayTurnDeadline deadline) {
        Objects.requireNonNull(text);
        deadline.requireActive();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("neuringo:text-input:utf16:v1\0".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                digest.update((byte) (value >>> 8));
                digest.update((byte) value);
            }
            deadline.requireActive();
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
