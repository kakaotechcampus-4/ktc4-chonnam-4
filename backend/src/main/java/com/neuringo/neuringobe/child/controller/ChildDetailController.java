package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.dto.UpdateChildRequest;
import com.neuringo.neuringobe.child.service.ChildService;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아동 한 명 조회·이름 수정(팀 API 문서 05 `GET·PATCH /children/{childId}`). 학급 경로 아래의 {@link ChildController} 와
 * 경로 뿌리가 달라 따로 둔다. 문서 응답의 accessCodeExpiresAt·removalPolicy 는 그 기능이 생길 때 붙인다.
 */
@RestController
public class ChildDetailController {

    private final ChildService childService;
    private final SensitiveReadLog sensitiveReads;

    public ChildDetailController(ChildService childService, SensitiveReadLog sensitiveReads) {
        this.childService = childService;
        this.sensitiveReads = sensitiveReads;
    }

    @GetMapping("/api/v1/children/{childId}")
    public ApiResponse<ChildResponse> get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID childId) {
        ChildResponse child =
                sensitiveReads.read(
                        user.userId(),
                        SensitiveReadLog.Target.CHILD,
                        childId,
                        () -> childService.get(user.userId(), childId));
        return ApiResponse.of(child);
    }

    /** 응답의 이름은 강사가 방금 보낸 값이라 조회 기록을 남기지 않는다(아동 등록과 같다). */
    @PatchMapping("/api/v1/children/{childId}")
    public ApiResponse<ChildResponse> rename(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID childId,
            @RequestBody @Valid UpdateChildRequest request) {
        return ApiResponse.of(childService.rename(user.userId(), childId, request));
    }
}
