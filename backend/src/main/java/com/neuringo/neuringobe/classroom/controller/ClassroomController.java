package com.neuringo.neuringobe.classroom.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.classroom.dto.ClassroomResponse;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
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
@RequestMapping("/api/v1/classrooms")
public class ClassroomController {

    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @PostMapping
    public ApiResponse<ClassroomResponse> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody @Valid CreateClassroomRequest request) {
        return ApiResponse.of(classroomService.create(user.userId(), request));
    }

    @GetMapping
    public ApiResponse<List<ClassroomResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(classroomService.list(user.userId()));
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassroomResponse> get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID classId) {
        return ApiResponse.of(classroomService.get(user.userId(), classId));
    }
}
