package com.neuringo.neuringobe.child.service;

import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ChildService {

    private final ChildRepository childRepository;
    private final ClassroomService classroomService;

    public ChildService(ChildRepository childRepository, ClassroomService classroomService) {
        this.childRepository = childRepository;
        this.classroomService = classroomService;
    }

    @Transactional
    public ChildResponse create(UUID instructorId, UUID classId, CreateChildRequest request) {
        classroomService.requireOwnedClassroom(instructorId, classId);

        Child child =
                new Child(UUID.randomUUID(), classId, request.displayName(), ChildStatus.ACTIVE);

        return ChildResponse.from(childRepository.save(child));
    }

    public List<ChildResponse> list(UUID instructorId, UUID classId) {
        classroomService.requireOwnedClassroom(instructorId, classId);

        return childRepository.findByClassId(classId).stream().map(ChildResponse::from).toList();
    }

    /** 담당 학급의 아동만 보인다. 다른 강사의 아동도 없는 아동과 똑같이 404 다. */
    public ChildResponse get(UUID instructorId, UUID childId) {
        return childRepository
                .findById(childId)
                .filter(child -> classroomService.isOwnedBy(instructorId, child.getClassId()))
                .map(ChildResponse::from)
                .orElseThrow(
                        () -> new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다."));
    }
}
