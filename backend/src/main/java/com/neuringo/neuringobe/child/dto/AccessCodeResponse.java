package com.neuringo.neuringobe.child.dto;

import java.time.OffsetDateTime;

public record AccessCodeResponse(String accessCode, OffsetDateTime expiresAt) {}
