package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import java.util.Optional;

/**
 * 캐릭터 역할극에서 마스코트가 캐릭터 대사와 따로 힌트를 줄지 서버가 정한다. 같은 상황이면 늘 같은 결과라 테스트로 고정한다.
 *
 * <p>마스코트는 장면 밖에서 아동에게 방향을 알려 준다. 너무 자주 끼어들면 역할극이 퀴즈처럼 되므로, 직전 턴에 이미 힌트를 줬거나 지금 목표에서 상한만큼 줬으면 쉰다. 딴
 * 이야기·짜증은 대화를 바로잡아야 해서 예외다. 수치는 MVP 테스트 결과로 조정한다.
 */
final class MascotHintRule {

    /** 한 마이크로 목표에서 보여 줄 마스코트 힌트 수 상한. 그다음은 캐릭터가 S3 로 직접 말한다. */
    static final int MAX_HINTS_PER_MICRO_GOAL = 2;

    /** 같은 목표에서 이 유도 턴 이상이고 지원 수준이 S2 이상이면 막힌 것으로 본다. */
    static final int STUCK_SUPPORT_TURN = 2;

    /** 힌트를 주는 이유. 이유별 말하는 방식은 캐릭터 응답 생성 지시문(response-generation-character.md)에 있다. */
    enum Reason {
        HELP_REQUEST,
        STUCK,
        OFF_TOPIC,
        NEGATIVE_EXPRESSION,
        MISCONCEPTION
    }

    private MascotHintRule() {}

    static Optional<Reason> decide(RoleplayTurnInput input, AnalysisResult analysis) {
        if (input.scenario().aiCharacter() == null) return Optional.empty();
        String strategy = analysis.nextStrategy().type();
        String supportLevel = analysis.nextStrategy().recommendedSupportLevel();
        if ("CLOSE_ROLEPLAY".equals(strategy) || "GOAL_ACHIEVED".equals(analysis.learningState()))
            return Optional.empty();

        Optional<Reason> redirect = redirectReason(analysis);
        if (redirect.isPresent()) return redirect;

        RoleplayTurnInput.State state = input.state();
        if (state.previousTurnHadMascotHint()
                || state.currentMicroGoalMascotHints() >= MAX_HINTS_PER_MICRO_GOAL
                || "S3".equals(supportLevel)) return Optional.empty();
        if ("MISCONCEPTION".equals(analysis.learningState()))
            return Optional.of(Reason.MISCONCEPTION);
        if ("DONT_KNOW".equals(analysis.responseAct())
                || "HELP_REQUEST".equals(analysis.responseAct()))
            return Optional.of(Reason.HELP_REQUEST);
        if (state.currentMicroGoalSupportTurn() >= STUCK_SUPPORT_TURN && "S2".equals(supportLevel))
            return Optional.of(Reason.STUCK);
        return Optional.empty();
    }

    /** 딴 이야기·짜증은 연속 금지·상한과 관계없이 바로잡는다. */
    private static Optional<Reason> redirectReason(AnalysisResult analysis) {
        if ("NEGATIVE_EXPRESSION".equals(analysis.responseAct()))
            return Optional.of(Reason.NEGATIVE_EXPRESSION);
        if ("OFF_TOPIC_REMARK".equals(analysis.responseAct())
                || "OFF_TOPIC".equals(analysis.learningState()))
            return Optional.of(Reason.OFF_TOPIC);
        return Optional.empty();
    }
}
