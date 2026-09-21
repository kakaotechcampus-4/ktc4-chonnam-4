package com.neuringo.neuringobe.child;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChildRequest(@NotBlank @Size(max = 100) String displayName) {}
