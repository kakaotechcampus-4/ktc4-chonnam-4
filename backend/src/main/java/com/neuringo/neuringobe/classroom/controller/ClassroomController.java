package com.neuringo.neuringobe.classroom.controller;

import com.neuringo.neuringobe.classroom.dto.ClassroomResponse;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/classrooms")
public class ClassroomController {

    // 강사 인증이 아직 없어 모든 학급을 이 임시 강사 ID로 만든다.
    // TODO: 강사 인증·세션 방식이 정해지면 로그인한 강사의 ID로 교체한다.
    private static final String DEV_INSTRUCTOR_ID = "dev-instructor";

    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @PostMapping
    public ApiResponse<ClassroomResponse> create(
            @RequestBody @Valid CreateClassroomRequest request) {
        return ApiResponse.of(classroomService.create(DEV_INSTRUCTOR_ID, request));
    }

    @GetMapping
    public ApiResponse<List<ClassroomResponse>> list() {
        return ApiResponse.of(classroomService.list());
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassroomResponse> get(@PathVariable UUID classId) {
        return ApiResponse.of(classroomService.get(classId));
    }
}
