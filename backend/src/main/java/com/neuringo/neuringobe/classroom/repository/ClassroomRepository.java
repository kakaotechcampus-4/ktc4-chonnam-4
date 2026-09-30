package com.neuringo.neuringobe.classroom.repository;

import com.neuringo.neuringobe.classroom.domain.Classroom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// 강사 요청에서는 소유권 조건이 붙은 메서드만 쓴다. findById·findAll 은 담당 강사를 거르지 않는다.
public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    List<Classroom> findByInstructorId(UUID instructorId);

    Optional<Classroom> findByClassIdAndInstructorId(UUID classId, UUID instructorId);

    boolean existsByClassIdAndInstructorId(UUID classId, UUID instructorId);
}
