package com.neuringo.neuringobe.deletion.dto;

import jakarta.validation.constraints.NotBlank;

// 탈퇴는 되돌릴 수 없어 토큰만으로는 받지 않는다. 현재 비밀번호를 다시 받아 확인한다.
public record WithdrawalRequest(@NotBlank String password) {}
