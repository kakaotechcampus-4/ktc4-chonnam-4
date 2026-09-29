package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.child.dto.AccessCodeResponse;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.common.ApiDomainException;
import com.neuringo.neuringobe.common.ApiResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/children/{childId}/access-codes")
public class ChildAccessCodeController {

    private final ChildAccessCodeService service;

    public ChildAccessCodeController(ChildAccessCodeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AccessCodeResponse>> issue(
            @PathVariable UUID childId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiDomainException(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "강사 인증이 필요합니다.");
        }
        if (authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_INSTRUCTOR".equals(authority.getAuthority()))) {
            throw new ApiDomainException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "담당 강사만 발급할 수 있습니다.");
        }
        var issued = service.issue(childId, requestKey, authentication.getName());
        return ResponseEntity.created(
                        URI.create(
                                "/api/v1/children/" + childId + "/access-codes/" + issued.codeId()))
                .body(ApiResponse.of(issued.response()));
    }
}
