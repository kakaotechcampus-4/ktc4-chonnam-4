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

    public ChildController(
            ChildRepository childRepository, ClassroomRepository classroomRepository) {
        this.childRepository = childRepository;
        this.classroomRepository = classroomRepository;
    }

    @PostMapping
    public ApiResponse<ChildResponse> create(
            @PathVariable UUID classId, @RequestBody @Valid CreateChildRequest request) {
        classroomRepository
                .findById(classId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId));

        Child child =
                new Child(UUID.randomUUID(), classId, request.displayName(), ChildStatus.ACTIVE);

        Child savedChild = childRepository.save(child);

        return ApiResponse.of(ChildResponse.from(savedChild));
    }

    @GetMapping
    public ApiResponse<List<ChildResponse>> list(@PathVariable UUID classId) {
        classroomRepository
                .findById(classId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId));

        List<ChildResponse> children =
                childRepository.findByClassId(classId).stream().map(ChildResponse::from).toList();

        return ApiResponse.of(children);
    }
}
