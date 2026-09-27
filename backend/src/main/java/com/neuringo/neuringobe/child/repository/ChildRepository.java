package com.neuringo.neuringobe.child.repository;

import com.neuringo.neuringobe.child.domain.Child;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByClassId(UUID classId);
}
