package com.neuringo.neuringobe.goal.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 역할극 등장인물. 관계는 그 인물이 아이에게 누구인지(같은 반 친구·형제·선생님 등)라 인물에 붙여 둔다. 다음 목표를 정할 때 이름과 관계를 따로 되살린다. */
public record GoalCharacter(
        @NotBlank @Size(max = 50) @NameText String name, @Size(max = 30) String relation) {}
