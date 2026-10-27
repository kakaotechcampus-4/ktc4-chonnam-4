package com.neuringo.neuringobe.child.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 아동 이름 수정. 학급 이동·연령·접근 방식은 받지 않는다. */
public record UpdateChildRequest(@NotBlank @Size(max = 100) @NameText String displayName) {}
