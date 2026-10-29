package com.neuringo.neuringobe.roleplay.dto;

import com.neuringo.neuringobe.quiz.dto.InitialDifficultyEvidence;
import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;

/** 최초 세션 생성용 내부 입력이며 세션 저장이나 시작 성공을 뜻하지 않는다. */
public record InitialRoleplayStartInput(
        InitialDifficultyEvidence initialDifficulty, ApprovedScenarioVersion scenario) {}
