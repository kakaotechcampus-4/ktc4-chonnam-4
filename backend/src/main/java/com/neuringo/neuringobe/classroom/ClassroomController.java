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

    // TODO(human): 아래 세 메서드의 본문을 채워주세요.
    //
    // 1) create(request)
    //    - new Classroom(UUID.randomUUID(), "dev-instructor", request.name(),
    // ClassroomStatus.ACTIVE) 생성
    //      (instructorId는 인증이 없어서 임시 고정값 — TODO: G0 결정 후 실제 인증 주체로 교체)
    //    - classroomRepository.save(...)로 저장
    //    - ClassroomResponse.from(...)으로 변환 후 ApiResponse.of(...)로 감싸서 반환
    //
    // 2) list()
    //    - classroomRepository.findAll() 조회
    //    - 각 Classroom을 ClassroomResponse.from(...)으로 변환한 List로 만들어 ApiResponse.of(...)로 반환
    //      (List<Classroom> -> List<ClassroomResponse> 변환은 stream().map(...).toList() 참고)
    //
    // 3) get(classId)
    //    - classroomRepository.findById(classId) 조회 (Optional<Classroom> 반환)
    //    - 없으면 new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId)
    // 던지기
    //    - 있으면 ClassroomResponse.from(...)으로 변환 후 ApiResponse.of(...)로 반환

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
