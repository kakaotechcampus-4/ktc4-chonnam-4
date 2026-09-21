package com.neuringo.neuringobe.classroom;

import java.util.UUID;

public record ClassroomResponse(UUID classId, String instructorId, String name, ClassroomStatus status) {

    public static ClassroomResponse from(Classroom classroom) {
        return new ClassroomResponse(
                classroom.getClassId(),
                classroom.getInstructorId(),
                classroom.getName(),
                classroom.getStatus());
    }
}
