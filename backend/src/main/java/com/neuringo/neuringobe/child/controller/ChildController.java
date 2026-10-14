package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.auth.security.SensitiveReadLog;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.child.service.ChildService;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/classrooms/{classId}/children")
public class ChildController {

    private final ChildService childService;
    private final SensitiveReadLog sensitiveReads;

    public ChildController(ChildService childService, SensitiveReadLog sensitiveReads) {
        this.childService = childService;
        this.sensitiveReads = sensitiveReads;
    }

    @PostMapping
    public ApiResponse<ChildResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID classId,
            @RequestBody @Valid CreateChildRequest request) {
        return ApiResponse.of(childService.create(user.userId(), classId, request));
    }

    @GetMapping
    public ApiResponse<List<ChildResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID classId) {
        List<ChildResponse> children = childService.list(user.userId(), classId);
        sensitiveReads.record(user.userId(), SensitiveReadLog.Target.CLASSROOM_CHILDREN, classId);
        return ApiResponse.of(children);
    }
}
