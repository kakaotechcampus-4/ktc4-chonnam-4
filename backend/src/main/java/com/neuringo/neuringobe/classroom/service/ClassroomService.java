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
import org.springframework.transaction.annotation.Propagation;
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
    public void requireOwnedClassroom(UUID instructorId, UUID classId) {
        if (!classroomRepository.existsByClassIdAndInstructorId(classId, instructorId)) {
            throw notFound(classId);
        }
    }

    /**
     * 담당 학급 행을 잠근다. 없으면 {@link #requireOwnedClassroom} 과 같은 404 다. 학급 삭제와 아동 등록이 이 잠금을 같이 써서, 삭제가
     * 아동 목록을 읽은 뒤 새 아동이 끼어들지 못하게 한다. 기다리던 쪽은 삭제가 끝난 뒤 학급이 없어진 것을 보고 404 가 된다.
     *
     * <p>잠금은 부르는 쪽 트랜잭션이 끝날 때 풀리므로 트랜잭션 안에서만 부른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwnedClassroom(UUID instructorId, UUID classId) {
        classroomRepository
                .findOwnedForUpdate(classId, instructorId)
                .orElseThrow(() -> notFound(classId));
    }

    /** 요청한 강사가 담당하는 학급인지. 다른 도메인이 "없는 것처럼" 자기 오류 코드로 404 를 낼 때 쓴다. */
    public boolean isOwnedBy(UUID instructorId, UUID classId) {
        return classroomRepository.existsByClassIdAndInstructorId(classId, instructorId);
    }

    private ResourceNotFoundException notFound(UUID classId) {
        return new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다: " + classId);
    }
}
