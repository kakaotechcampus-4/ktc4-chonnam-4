package com.neuringo.neuringobe.classroom.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(@NotBlank @Size(max = 100) @NameText String name) {}
