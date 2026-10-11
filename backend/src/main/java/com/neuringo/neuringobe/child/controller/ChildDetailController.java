package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.service.ChildService;
import com.neuringo.neuringobe.common.ApiResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아동 한 명 조회(정본 05 `GET /children/{childId}`). 학급 경로 아래의 {@link ChildController} 와 경로 뿌리가 달라 따로 둔다.
 * 정본 응답의 accessCodeExpiresAt·removalPolicy 는 그 기능이 생길 때 붙인다.
 */
@RestController
public class ChildDetailController {

    private final ChildService childService;

    public ChildDetailController(ChildService childService) {
        this.childService = childService;
    }

    @GetMapping("/api/v1/children/{childId}")
    public ApiResponse<ChildResponse> get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID childId) {
        return ApiResponse.of(childService.get(user.userId(), childId));
    }
}
