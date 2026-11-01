package com.neuringo.neuringobe.scenario.dto;

import com.neuringo.neuringobe.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 초안을 고치거나 판정한다(명세 review-scenario). 칸을 고친 뒤 status 가 있으면 판정한다. APPROVED·REJECTED 를 받는다.
 * REVISION_REQUIRED 는 다시 만들기(생성)가 들어오면 받는다.
 */
public record ReviewScenarioRequest(
        @NotNull @Positive Integer version,
        String status,
        @Size(max = 200) @NoControlCharacters String title,
        @Size(max = 1000) @NoControlCharacters String summary,
        List<@Valid DraftMicroGoalPatch> microGoals) {}
