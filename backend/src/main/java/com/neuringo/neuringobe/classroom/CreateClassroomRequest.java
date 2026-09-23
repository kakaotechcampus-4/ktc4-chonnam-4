package com.neuringo.neuringobe.classroom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(@NotBlank @Size(max = 100) String name) {}
