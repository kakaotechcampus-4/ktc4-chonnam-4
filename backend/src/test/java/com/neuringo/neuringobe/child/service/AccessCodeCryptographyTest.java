package com.neuringo.neuringobe.child.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessCodeCryptographyTest {

    private final AccessCodeCryptography cryptography =
            new AccessCodeCryptography("test-only-hmac-secret-that-is-at-least-32-bytes-long");

    @Test
    void coversEveryFourDigitCodeOnceAndReplaysDeterministically() {
        UUID childId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        HashSet<String> candidates = new HashSet<>();
        for (int counter = 0; counter < 10_000; counter++) {
            String code = cryptography.deriveCode(childId, key, counter);
            assertThat(code).matches("[0-9]{4}");
            candidates.add(code);
        }
        assertThat(candidates).hasSize(10_000).contains("0047");
        assertThat(cryptography.deriveCode(childId, key, 0))
                .isEqualTo(cryptography.deriveCode(childId, key, 0));
        assertThat(cryptography.digest("0047")).doesNotContain("0047");
    }
}
