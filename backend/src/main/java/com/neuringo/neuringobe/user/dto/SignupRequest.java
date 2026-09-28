package com.neuringo.neuringobe.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String orgName) {

    private static final int BCRYPT_MAX_BYTES = 72;

    // BCrypt 는 72바이트까지만 받는다(초과하면 인코더가 예외를 던져 500 이 된다). 한글은 한 글자가 3바이트라
    // 글자 수 검사만으로는 막을 수 없어 바이트 수를 따로 확인한다.
    @AssertTrue(message = "비밀번호가 너무 깁니다.")
    public boolean isPasswordWithinByteLimit() {
        return password == null
                || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
