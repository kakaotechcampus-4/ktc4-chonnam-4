package com.neuringo.neuringobe.report.application;

import com.neuringo.neuringobe.report.domain.EvidenceSufficiency;
import com.neuringo.neuringobe.report.domain.RecommendationCandidate;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import com.neuringo.neuringobe.scenario.domain.ScenarioLevel;
import com.neuringo.neuringobe.scenario.domain.SupportLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 추천 정책의 후보를 저장할 추천으로 옮긴다. 계약(list-recommendations)대로 최대 3개이고, 이유 문장은 강사 화면 문구라 해요체로 쓴다.
 *
 * <p>다음 활동의 목표는 지금 학습 목표를 이어받는다(parentGoalId). 보완·반복·적용은 같은 목표를 다른 승인 시나리오로 다시 하는 것이라 제목을 그대로 제안하고,
 * 확장은 넓힐 목표를 강사가 정하도록 제목을 비워 둔다. 유사 목표 검색(임베딩)은 아직 운영에 붙이지 않았다.
 */
public final class RecommendationDrafts {

    public static final int MAX_RECOMMENDATIONS = 3;

    private RecommendationDrafts() {}

    /**
     * @param extendFromLowest L1(S2 시작) 역할극 만점에서도 확장을 낼지(팀 결정 대기, 기본 true = 지금 정책 그대로)
     */
    public static List<Draft> from(
            List<RecommendationCandidate> candidates,
            CurrentGoal currentGoal,
            ScenarioLevel playedLevel,
            boolean extendFromLowest) {
        List<Draft> drafts = new ArrayList<>();
        for (RecommendationCandidate candidate : candidates) {
            if (candidate.type() == RecommendationType.EXTEND
                    && playedLevel == ScenarioLevel.L1
                    && !extendFromLowest) {
                continue;
            }
            if (drafts.size() == MAX_RECOMMENDATIONS) {
                break;
            }
            String basis = candidate.basis() == null ? null : candidate.basis().goalTitle();
            drafts.add(
                    new Draft(
                            candidate.type(),
                            drafts.size() + 1,
                            candidate.reason().name(),
                            reason(candidate, basis),
                            basis,
                            candidate.evidence(),
                            candidate.sufficiency(),
                            suggested(candidate.type(), currentGoal)));
        }
        return drafts;
    }

    private static SuggestedGoal suggested(RecommendationType type, CurrentGoal current) {
        if (type == RecommendationType.EXTEND) {
            return new SuggestedGoal(null, current.category(), current.goalId());
        }
        return new SuggestedGoal(current.title(), current.category(), current.goalId());
    }

    static String reason(RecommendationCandidate candidate, String basis) {
        String goal = basis == null ? "이 목표" : "'" + basis + "' 목표";
        return switch (candidate.reason()) {
            case GOAL_NOT_REACHED -> goal + "에 아직 도달하지 못했어요. 같은 목표를 다른 장면에서 다시 해 봐요.";
            case GOAL_NEEDED_ANSWER -> goal + "는 답을 듣고 따라 말했어요. 같은 목표를 다시 연습해 봐요.";
            case REACHED_WITH_EXTRA_SUPPORT ->
                    goal
                            + "는 "
                            + supportWithObject(candidate.basis().outcome().finalSupport())
                            + " 받고 도달했어요. 다른 장면으로 한 번 더 해 봐요.";
            case REACHED_WITHOUT_EXTRA_SUPPORT -> goal + "는 처음 준 지원만으로 도달했어요. 새 장면에 써 봐요.";
            // 확장 조건은 점수율 80% 이상이고 답을 듣거나 놓친 목표가 없는 것이다. 지원을 더 받은 목표가 있어도 확장이 나올 수 있다.
            case ALL_GOALS_WITHOUT_EXTRA_SUPPORT -> "모든 작은 목표에 답을 듣지 않고 도달했어요. 한 단계 넓힌 목표를 골라 주세요.";
            case NOT_ENOUGH_EVIDENCE -> "판단할 기록이 부족해요. 같은 목표를 한 번 더 해 봐요.";
        };
    }

    private static String supportWithObject(SupportLevel level) {
        return switch (level) {
            case S0 -> "열린 질문을";
            case S1 -> "상황 단서를";
            case S2 -> "두 선택지를";
            case S3 -> "답 알려 주기를";
        };
    }

    /** 지금 활동의 학습 목표. 다음 활동 목표의 parentGoalId 가 된다. */
    public record CurrentGoal(UUID goalId, String title, String category) {}

    /** 다음 활동에 제안하는 목표. title 이 null 이면 강사가 새로 쓴다. */
    public record SuggestedGoal(String title, String category, UUID parentGoalId) {}

    public record Draft(
            RecommendationType type,
            int displayOrder,
            String reasonCode,
            String reason,
            String basisGoalTitle,
            List<String> evidence,
            EvidenceSufficiency sufficiency,
            SuggestedGoal suggestedGoal) {}
}
