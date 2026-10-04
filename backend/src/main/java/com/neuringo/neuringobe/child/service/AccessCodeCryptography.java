package com.neuringo.neuringobe.child.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps the four-digit code out of persistent storage while allowing indexed lookup. */
@Component
public class AccessCodeCryptography {

    private final byte[] secret;

    public AccessCodeCryptography(@Value("${neuringo.child-access.hmac-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String deriveCode(UUID childId, UUID requestKey, int counter) {
        byte[] digest = hmac("issue:" + childId + ":" + requestKey);
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        long start = Integer.toUnsignedLong(bytes.getInt()) % 10_000;
        int step = (int) (Integer.toUnsignedLong(bytes.getInt()) % 10_000);
        // A step coprime with 10,000 visits every code exactly once before exhaustion.
        while (step % 2 == 0 || step % 5 == 0) {
            step = (step + 1) % 10_000;
        }
        return "%04d".formatted((start + (long) counter * step) % 10_000);
    }

    public String digest(String code) {
        return HexFormat.of().formatHex(hmac("lookup:" + code));
    }

    private byte[] hmac(String text) {
        if (secret.length < 32) {
            throw new IllegalStateException("아동 접근 코드 HMAC 비밀키가 설정되지 않았습니다.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("아동 접근 코드 암호화 기능을 사용할 수 없습니다.", ex);
        }
    }
}
