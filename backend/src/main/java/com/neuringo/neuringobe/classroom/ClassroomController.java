package com.neuringo.neuringobe.classroom;

import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
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
@RequestMapping("/classrooms")
public class ClassroomController {

    private final ClassroomRepository classroomRepository;

    public ClassroomController(ClassroomRepository classroomRepository) {
        this.classroomRepository = classroomRepository;
    }

    // instructorId 는 인증이 없어 임시 고정값이다. TODO: G0 결정 후 실제 인증 주체로 교체.
    @PostMapping
    public ApiResponse<ClassroomResponse> create(
            @RequestBody @Valid CreateClassroomRequest request) {
        Classroom classroom =
                new Classroom(
                        UUID.randomUUID(),
                        "dev-instructor",
                        request.name(),
                        ClassroomStatus.ACTIVE);

        Classroom savedClassroom = classroomRepository.save(classroom);

        return ApiResponse.of(ClassroomResponse.from(savedClassroom));
    }

    @GetMapping
    public ApiResponse<List<ClassroomResponse>> list() {
        List<ClassroomResponse> classrooms =
                classroomRepository.findAll().stream().map(ClassroomResponse::from).toList();

        return ApiResponse.of(classrooms);
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassroomResponse> get(@PathVariable UUID classId) {
        Classroom classroom =
                classroomRepository
                        .findById(classId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "CLASSROOM_NOT_FOUND",
                                                "학급을 찾을 수 없습니다: " + classId));

        return ApiResponse.of(ClassroomResponse.from(classroom));
    }
}
