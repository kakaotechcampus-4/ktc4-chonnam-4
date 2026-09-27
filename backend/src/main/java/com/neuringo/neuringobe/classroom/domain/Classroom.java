package com.neuringo.neuringobe.classroom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "classroom")
public class Classroom {

    @Id
    @Column(name = "class_id")
    private UUID classId;

    @Column(name = "instructor_id", nullable = false)
    private String instructorId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClassroomStatus status;

    protected Classroom() {}

    public Classroom(UUID classId, String instructorId, String name, ClassroomStatus status) {
        this.classId = classId;
        this.instructorId = instructorId;
        this.name = name;
        this.status = status;
    }

    public UUID getClassId() {
        return classId;
    }

    public String getInstructorId() {
        return instructorId;
    }

    public String getName() {
        return name;
    }

    public ClassroomStatus getStatus() {
        return status;
    }
}
