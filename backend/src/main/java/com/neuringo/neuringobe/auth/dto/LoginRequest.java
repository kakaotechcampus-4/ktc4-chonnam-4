package com.neuringo.neuringobe.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 로그인은 가입 규칙(길이 등)을 검사하지 않는다. 틀린 값은 모두 INVALID_CREDENTIALS 로 응답한다.
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
