package com.neuringo.neuringobe.classroom.controller;

import com.neuringo.neuringobe.classroom.dto.ClassroomResponse;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ApiDomainException;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
            @RequestBody @Valid CreateClassroomRequest request, Authentication authentication) {
        return ApiResponse.of(classroomService.create(instructorName(authentication), request));
    }

    @GetMapping
    public ApiResponse<List<ClassroomResponse>> list(Authentication authentication) {
        return ApiResponse.of(classroomService.list(instructorName(authentication)));
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassroomResponse> get(
            @PathVariable UUID classId, Authentication authentication) {
        return ApiResponse.of(classroomService.get(classId, instructorName(authentication)));
    }

    private String instructorName(Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                authority -> "ROLE_INSTRUCTOR".equals(authority.getAuthority()))) {
            throw new ApiDomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "강사 인증이 필요합니다.");
        }
        return authentication.getName();
    }
}
