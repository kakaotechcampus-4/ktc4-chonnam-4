package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 승인본을 고칠 새 판(초안)을 만든다(명세 create-scenario-version). version 은 고치려는 원본의 번호다. goalId 는 원본에 목표가 없을
 * 때(공용 승인본) 반드시 보내고, 원본에 목표가 있으면 생략해 이어받는다. 나머지 칸은 처음부터 고친 값으로 만들 때만 보낸다.
 */
public record CreateScenarioVersionRequest(
        @NotNull @Positive Integer version,
        UUID goalId,
        @Size(max = 200) @NoControlCharacters String title,
        @Size(max = 1000) @NoControlCharacters String summary,
        List<@Valid DraftMicroGoalPatch> microGoals) {}
