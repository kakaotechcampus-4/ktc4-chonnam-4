package com.neuringo.neuringobe.classroom;

import jakarta.validation.constraints.NotBlank;

public record CreateClassroomRequest(@NotBlank String name) {}
