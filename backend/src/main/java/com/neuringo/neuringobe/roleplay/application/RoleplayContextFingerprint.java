package com.neuringo.neuringobe.roleplay.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** 분석에 쓴 자료와 재조회 자료의 일치 확인용이다. 지문은 요청 메모리에서만 사용한다. */
public final class RoleplayContextFingerprint {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RoleplayContextFingerprint() {}

    public static String of(RoleplayContextSource.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(JSON.writeValueAsBytes(snapshot)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("분석 자료의 지문을 계산할 수 없습니다.", impossible);
        }
    }
}
