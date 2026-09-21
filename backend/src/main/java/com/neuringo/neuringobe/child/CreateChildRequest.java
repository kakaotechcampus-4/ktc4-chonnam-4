package com.neuringo.neuringobe.child;

import jakarta.validation.constraints.NotBlank;

public record CreateChildRequest(@NotBlank String displayName) {}
