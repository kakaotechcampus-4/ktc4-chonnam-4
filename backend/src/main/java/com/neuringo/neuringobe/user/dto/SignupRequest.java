package com.neuringo.neuringobe.user.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import com.neuringo.neuringobe.common.validation.NoControlCharacters;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) @NoControlCharacters String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) @NameText String name,
        @Size(max = 100) @NoControlCharacters String orgName) {

    private static final int BCRYPT_MAX_BYTES = 72;

    // 검증(@Email)보다 먼저 앞뒤 공백을 지운다. 복사·붙여넣기로 붙은 공백 때문에 가입이 422 로 실패하지 않게 한다.
    public SignupRequest {
        email = email == null ? null : email.trim();
    }

    // BCrypt 는 72바이트까지만 받는다(초과하면 인코더가 예외를 던져 500 이 된다). 한글은 한 글자가 3바이트라
    // 글자 수 검사만으로는 막을 수 없어 바이트 수를 따로 확인한다.
    @AssertTrue(message = "비밀번호가 너무 깁니다.")
    public boolean isPasswordWithinByteLimit() {
        return password == null
                || password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
