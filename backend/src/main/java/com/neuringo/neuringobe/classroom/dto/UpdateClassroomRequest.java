package com.neuringo.neuringobe.classroom.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 학급명 수정. 시안의 다른 칸(연령·기관·메모 등)은 저장할 곳이 없어 받지 않는다. */
public record UpdateClassroomRequest(@NotBlank @Size(max = 100) @NameText String name) {}
