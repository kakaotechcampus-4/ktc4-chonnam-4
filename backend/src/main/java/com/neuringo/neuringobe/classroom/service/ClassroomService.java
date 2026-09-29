package com.neuringo.neuringobe.classroom.service;

import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.dto.ClassroomResponse;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ClassroomService {

    private final ClassroomRepository classroomRepository;

    public ClassroomService(ClassroomRepository classroomRepository) {
        this.classroomRepository = classroomRepository;
    }

    @Transactional
    public ClassroomResponse create(String instructorId, CreateClassroomRequest request) {
        Classroom classroom =
                new Classroom(
                        UUID.randomUUID(), instructorId, request.name(), ClassroomStatus.ACTIVE);

        return ClassroomResponse.from(classroomRepository.save(classroom));
    }

    public List<ClassroomResponse> list(String instructorId) {
        return classroomRepository.findByInstructorId(instructorId).stream()
                .map(ClassroomResponse::from)
                .toList();
    }

    public ClassroomResponse get(UUID classId, String instructorId) {
        return ClassroomResponse.from(requireOwned(classId, instructorId));
    }

    public void validateClassroomOwned(UUID classId, String instructorId) {
        requireOwned(classId, instructorId);
    }

    private Classroom requireOwned(UUID classId, String instructorId) {
        Classroom classroom =
                classroomRepository.findById(classId).orElseThrow(() -> notFound(classId));
        if (!classroom.getInstructorId().equals(instructorId)) {
            throw notFound(classId);
        }
        return classroom;
    }

    private ResourceNotFoundException notFound(UUID classId) {
        return new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId);
    }
}
