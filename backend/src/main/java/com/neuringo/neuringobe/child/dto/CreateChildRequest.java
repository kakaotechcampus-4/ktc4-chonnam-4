package com.neuringo.neuringobe.child.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChildRequest(@NotBlank @Size(max = 100) String displayName) {}
