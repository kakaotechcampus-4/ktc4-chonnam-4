package com.neuringo.neuringobe.user.dto;

import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserAccount;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        UserRole role,
        String email,
        String name,
        String orgName,
        AccountStatus status) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(
                user.getUserId(),
                user.getRole(),
                user.getEmail(),
                user.getName(),
                user.getOrgName(),
                user.getStatus());
    }
}
