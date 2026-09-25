package com.neuringo.neuringobe.classroom.repository;

import com.neuringo.neuringobe.classroom.domain.Classroom;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {}
