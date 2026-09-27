package com.neuringo.neuringobe.child.service;

import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
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
    public ChildResponse create(UUID classId, CreateChildRequest request) {
        classroomService.validateClassroomExists(classId);

        Child child =
                new Child(UUID.randomUUID(), classId, request.displayName(), ChildStatus.ACTIVE);

        return ChildResponse.from(childRepository.save(child));
    }

    public List<ChildResponse> list(UUID classId) {
        classroomService.validateClassroomExists(classId);

        return childRepository.findByClassId(classId).stream().map(ChildResponse::from).toList();
    }
}
