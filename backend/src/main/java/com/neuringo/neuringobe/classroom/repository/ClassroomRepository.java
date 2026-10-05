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

    /**
     * 학급 삭제와 아동 등록이 같이 쓰는 잠금(ADR 2026-10-04 D2). PostgreSQL 에서 {@code FOR NO KEY UPDATE} 가 되어 아동
     * INSERT 의 FK 검사({@code KEY SHARE})는 막지 못한다. 그래서 아동 등록도 FK 에 기대지 않고 이 잠금을 직접 잡는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select c from Classroom c where c.classId = :classId and c.instructorId = :instructorId")
    Optional<Classroom> findOwnedForUpdate(
            @Param("classId") UUID classId, @Param("instructorId") UUID instructorId);

    @Modifying
    @Query(value = "delete from classroom where class_id = :classId", nativeQuery = true)
    int deleteByClassId(@Param("classId") UUID classId);
}
