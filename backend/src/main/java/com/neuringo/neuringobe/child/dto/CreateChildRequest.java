package com.neuringo.neuringobe.child.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChildRequest(@NotBlank @Size(max = 100) @NameText String displayName) {}
