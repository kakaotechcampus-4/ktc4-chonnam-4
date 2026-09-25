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

    /**
     * 모든 학급을 반환한다. 담당 강사로 거르지 않는다.
     *
     * <p>TODO: 강사 인증·세션 방식이 정해지면 담당 강사의 학급만 반환하도록 바꾼다(정본: 목록 권한 필터는 DB 조회 조건에 포함).
     */
    public List<ClassroomResponse> list() {
        return classroomRepository.findAll().stream().map(ClassroomResponse::from).toList();
    }

    public ClassroomResponse get(UUID classId) {
        Classroom classroom =
                classroomRepository.findById(classId).orElseThrow(() -> notFound(classId));

        return ClassroomResponse.from(classroom);
    }

    /**
     * 학급이 없으면 {@link ResourceNotFoundException}(CLASSROOM_NOT_FOUND)을 던진다. 존재 여부만 확인하며 학급
     * 상태(ACTIVE·ARCHIVED)는 검사하지 않는다.
     *
     * <p>TODO: 강사 인증·세션 방식이 정해지면 요청한 강사가 담당하는 학급인지도 확인한다(정본: 권한 밖 리소스도 404). ARCHIVED 학급에 대한 등록·조회
     * 규칙이 정해지면 상태 검사는 별도 메서드로 둔다.
     */
    public void validateClassroomExists(UUID classId) {
        if (!classroomRepository.existsById(classId)) {
            throw notFound(classId);
        }
    }

    private ResourceNotFoundException notFound(UUID classId) {
        return new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId);
    }
}
