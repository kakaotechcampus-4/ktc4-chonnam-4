package com.neuringo.neuringobe.auth.dto;

import com.neuringo.neuringobe.user.dto.UserResponse;
import java.time.Instant;

public record LoginResponse(String accessToken, Instant expiresAt, UserResponse user) {}
