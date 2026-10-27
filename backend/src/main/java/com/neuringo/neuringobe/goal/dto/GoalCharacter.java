package com.neuringo.neuringobe.goal.dto;

import com.neuringo.neuringobe.common.validation.NameText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 역할극 등장인물. 팀 명세 예시와 시나리오 생성 입력·출력이 같은 {@code {id, role}} 모양이다.
 *
 * <p>{@code role} 은 그 인물의 설명(예: "넘어진 친구")이고, {@code id} 는 아이와의 관계 종류(예: {@code FRIEND})다. 실제 이름은 받지
 * 않는다. {@code id} 는 비워도 되고, 비면 저장할 때 {@code OTHER} 가 된다.
 */
public record GoalCharacter(
        @Size(max = 30)
                @Pattern(
                        regexp = "([A-Z][A-Z0-9_]*)?",
                        message = "영문 대문자로 시작하고 영문 대문자·숫자·밑줄만 쓸 수 있습니다.")
                String id,
        @NotBlank @Size(max = 50) @NameText String role) {}
