package com.neuringo.neuringobe.classroom.repository;

import com.neuringo.neuringobe.classroom.domain.Classroom;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 강사 요청에서는 소유권 조건이 붙은 메서드만 쓴다. findById·findAll 은 담당 강사를 거르지 않는다.
public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    List<Classroom> findByInstructorId(UUID instructorId);

    Optional<Classroom> findByClassIdAndInstructorId(UUID classId, UUID instructorId);

    boolean existsByClassIdAndInstructorId(UUID classId, UUID instructorId);

    /** 학급 삭제(ADR 2026-10-04 D2). 잠근 뒤 아동 목록을 읽어, 그 사이 등록된 아동이 빠지지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select c from Classroom c where c.classId = :classId and c.instructorId = :instructorId")
    Optional<Classroom> findOwnedForUpdate(
            @Param("classId") UUID classId, @Param("instructorId") UUID instructorId);

    @Modifying
    @Query(value = "delete from classroom where class_id = :classId", nativeQuery = true)
    int deleteByClassId(@Param("classId") UUID classId);
}
