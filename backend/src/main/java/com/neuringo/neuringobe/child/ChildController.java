package com.neuringo.neuringobe.child;

import com.neuringo.neuringobe.classroom.ClassroomRepository;
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
@RequestMapping("/classrooms/{classId}/children")
public class ChildController {

    private final ChildRepository childRepository;
    private final ClassroomRepository classroomRepository;

    public ChildController(ChildRepository childRepository, ClassroomRepository classroomRepository) {
        this.childRepository = childRepository;
        this.classroomRepository = classroomRepository;
    }

    // TODO(human): 아래 두 메서드의 본문을 채워주세요.
    //
    // 두 메서드 다 시작하기 전에 먼저 classId로 학급이 있는지부터 확인해야 합니다
    // (없는 학급 밑에 아동을 등록하거나 조회하면 안 되니까요):
    //   classroomRepository.findById(classId)
    //       .orElseThrow(() -> new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId));
    // Classroom 자체는 안 써도 괜찮습니다 — "존재하는지 확인"이 목적이라
    // 결과를 변수에 담지 않고 그냥 호출만 해도 됩니다.
    //
    // 1) create(classId, request)
    //    - 위 존재 확인 먼저
    //    - new Child(UUID.randomUUID(), classId, request.displayName(), ChildStatus.ACTIVE) 생성
    //    - childRepository.save(...)로 저장
    //    - ChildResponse.from(...)으로 변환 후 ApiResponse.of(...)로 반환
    //
    // 2) list(classId)
    //    - 위 존재 확인 먼저
    //    - childRepository.findByClassId(classId) 조회 (Classroom 때와 달리 findAll이 아님에 주의)
    //    - List<ChildResponse>로 변환(stream().map(ChildResponse::from).toList()) 후 ApiResponse.of(...)로 반환

    @PostMapping
    public ApiResponse<ChildResponse> create(
            @PathVariable UUID classId, @RequestBody @Valid CreateChildRequest request) {
        classroomRepository.findById(classId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CLASSROOM_NOT_FOUND",
                        "학급을 찾을 수 없습니다: " + classId));

        Child child = new Child(
                UUID.randomUUID(),
                classId,
                request.displayName(),
                ChildStatus.ACTIVE);

        Child savedChild = childRepository.save(child);

        return ApiResponse.of(ChildResponse.from(savedChild));
    }

    @GetMapping
    public ApiResponse<List<ChildResponse>> list(@PathVariable UUID classId) {
        classroomRepository.findById(classId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CLASSROOM_NOT_FOUND",
                        "학급을 찾을 수 없습니다: " + classId));

        List<ChildResponse> children = childRepository.findByClassId(classId)
                .stream()
                .map(ChildResponse::from)
                .toList();

        return ApiResponse.of(children);
    }
}
