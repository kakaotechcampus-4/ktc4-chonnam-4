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
    public ClassroomResponse create(UUID instructorId, CreateClassroomRequest request) {
        Classroom classroom =
                new Classroom(
                        UUID.randomUUID(), instructorId, request.name(), ClassroomStatus.ACTIVE);

        return ClassroomResponse.from(classroomRepository.save(classroom));
    }

    /** 담당 강사의 학급만 반환한다. 권한 필터는 DB 조회 조건에 둔다(정본: 목록을 가져온 뒤 거르지 않는다). */
    public List<ClassroomResponse> list(UUID instructorId) {
        return classroomRepository.findByInstructorId(instructorId).stream()
                .map(ClassroomResponse::from)
                .toList();
    }

    public ClassroomResponse get(UUID instructorId, UUID classId) {
        Classroom classroom =
                classroomRepository
                        .findByClassIdAndInstructorId(classId, instructorId)
                        .orElseThrow(() -> notFound(classId));

        return ClassroomResponse.from(classroom);
    }

    /**
     * 요청한 강사가 담당하는 학급이 아니면 {@link ResourceNotFoundException}(CLASSROOM_NOT_FOUND)을 던진다. 다른 강사의 학급도
     * 없는 학급과 똑같이 404 로 응답해 존재 여부를 드러내지 않는다(정본: 권한 밖 리소스도 404). 학급 상태(ACTIVE·ARCHIVED)는 검사하지 않는다.
     *
     * <p>TODO: ARCHIVED 학급에 대한 등록·조회 규칙이 정해지면 상태 검사는 별도 메서드로 둔다.
     */
    public void validateClassroomExists(UUID instructorId, UUID classId) {
        if (!classroomRepository.existsByClassIdAndInstructorId(classId, instructorId)) {
            throw notFound(classId);
        }
    }

    private ResourceNotFoundException notFound(UUID classId) {
        return new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId);
    }
}
