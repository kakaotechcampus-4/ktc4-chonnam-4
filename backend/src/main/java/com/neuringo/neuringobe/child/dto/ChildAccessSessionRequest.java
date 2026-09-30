package com.neuringo.neuringobe.child.dto;

import jakarta.validation.constraints.Pattern;

public record ChildAccessSessionRequest(
        @Pattern(regexp = "[0-9]{4}", message = "입장 코드는 숫자 4자리여야 합니다.") String accessCode) {}
