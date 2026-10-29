package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.roleplay.dto.RoleplayStartResponse;
import com.neuringo.neuringobe.roleplay.service.RoleplayStartService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoleplayStartController {
    private final RoleplayStartService starts;

    public RoleplayStartController(RoleplayStartService starts) {
        this.starts = starts;
    }

    @PostMapping("/api/v1/activities/{activityId}/roleplay-sessions")
    public ResponseEntity<ApiResponse<RoleplayStartResponse>> start(
            @PathVariable UUID activityId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @RequestBody(required = false) String body,
            Authentication authentication) {
        if (body != null && !body.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "역할극 시작 요청에는 본문을 보내지 않습니다.");
        }
        var result = starts.startForDelivery(activityId, requestKey, authentication);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(result.response()));
    }
}
