package com.neuringo.neuringobe.report.application;

import static com.neuringo.neuringobe.report.domain.ReportFixtures.goal;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.NOT_REACHED;
import static com.neuringo.neuringobe.scenario.domain.GoalOutcome.Result.REACHED;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S0;
import static com.neuringo.neuringobe.scenario.domain.SupportLevel.S2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neuringo.neuringobe.report.application.port.SimilarGoal;
import com.neuringo.neuringobe.report.application.similarity.InMemoryGoalSimilaritySearch;
import com.neuringo.neuringobe.report.domain.GoalEvidence;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.RecommendationRules;
import com.neuringo.neuringobe.report.domain.RecommendationType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 확장 추천의 다음 목표를 유사도로 고르는 규칙. 임베딩은 문장마다 정해 둔 벡터로 대신한다. */
class RecommendationComposerTest {

    private static final UUID CLASS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_CLASS_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID CURRENT_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID REWORDED_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID RELATED_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID UNRELATED_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID COMPLETED_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID OTHER_CLASS_GOAL =
            UUID.fromString("00000000-0000-0000-0000-0000000000a5");

    private final Map<String, float[]> vectors = new HashMap<>();
    private InMemoryGoalSimilaritySearch search;
    private RecommendationComposer composer;

    @BeforeEach
    void setUp() {
        vectors.put("감정과 이유 연결하기", new float[] {1, 0, 0});
        vectors.put("감정과 이유를 연결해 말하기", new float[] {1, 0, 0.01f});
        vectors.put("상대 감정의 이유 말하기", new float[] {0.8f, 0.6f, 0});
        vectors.put("줄 서서 기다리기", new float[] {0, 0, 1});
        vectors.put("감정 이름 말하기", new float[] {0.95f, 0.05f, 0});
        vectors.put("다른 반 목표", new float[] {1, 0, 0});
        search = new InMemoryGoalSimilaritySearch(text -> vectors.get(text));
        search.index(CLASS_ID, CURRENT_GOAL, "감정과 이유 연결하기");
        search.index(CLASS_ID, REWORDED_GOAL, "감정과 이유를 연결해 말하기");
        search.index(CLASS_ID, RELATED_GOAL, "상대 감정의 이유 말하기");
        search.index(CLASS_ID, UNRELATED_GOAL, "줄 서서 기다리기");
        search.index(CLASS_ID, COMPLETED_GOAL, "감정 이름 말하기");
        search.index(OTHER_CLASS_ID, OTHER_CLASS_GOAL, "다른 반 목표");
        composer =
                new RecommendationComposer(
                        new RecommendationPolicy(RecommendationRules.V1), search);
    }

    @Test
    void suggestsTheClosestNonDuplicateGoalForExtension() {
        List<Recommendation> recommendations = composer.compose(request(allIndependent()));

        Recommendation extend = byType(recommendations, RecommendationType.EXTEND);
        assertThat(extend.suggestedGoal().goalId()).isEqualTo(RELATED_GOAL);
        assertThat(extend.suggestedGoal().similarity()).isCloseTo(0.8, within());
        assertThat(byType(recommendations, RecommendationType.APPLY).suggestedGoal()).isNull();
    }

    @Test
    void leavesSuggestionEmptyWhenNoGoalIsCloseEnough() {
        search.remove(CLASS_ID, RELATED_GOAL);

        Recommendation extend =
                byType(composer.compose(request(allIndependent())), RecommendationType.EXTEND);

        assertThat(extend.suggestedGoal()).isNull();
    }

    @Test
    void doesNotSearchForNonExtensionCandidates() {
        List<Recommendation> recommendations =
                composer.compose(
                        request(
                                List.of(
                                        goal("감정 이름", S0, S0, REACHED),
                                        goal("감정 이유", S0, S2, NOT_REACHED))));

        assertThat(recommendations)
                .allSatisfy(recommendation -> assertThat(recommendation.suggestedGoal()).isNull());
    }

    @Test
    void searchStaysInsideTheClassAndHonorsExclusionsAndLimit() {
        List<SimilarGoal> found =
                search.findSimilar(CLASS_ID, "감정과 이유 연결하기", Set.of(CURRENT_GOAL), 2);

        assertThat(found)
                .extracting(SimilarGoal::goalId)
                .containsExactly(REWORDED_GOAL, COMPLETED_GOAL);
        assertThat(search.findSimilar(OTHER_CLASS_ID, "감정과 이유 연결하기", Set.of(), 10))
                .extracting(SimilarGoal::goalId)
                .containsExactly(OTHER_CLASS_GOAL);
    }

    @Test
    void rejectsVectorsOfDifferentDimensions() {
        vectors.put("짧은 벡터", new float[] {1, 0});

        assertThatThrownBy(() -> search.findSimilar(CLASS_ID, "짧은 벡터", Set.of(), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<GoalEvidence> allIndependent() {
        return List.of(goal("감정 이름", S0, S0, REACHED), goal("감정 이유", S0, S0, REACHED));
    }

    private static RecommendationRequest request(List<GoalEvidence> goals) {
        return new RecommendationRequest(
                CLASS_ID, CURRENT_GOAL, "감정과 이유 연결하기", Set.of(COMPLETED_GOAL), goals);
    }

    private static Recommendation byType(
            List<Recommendation> recommendations, RecommendationType type) {
        return recommendations.stream()
                .filter(recommendation -> recommendation.candidate().type() == type)
                .findFirst()
                .orElseThrow();
    }

    private static Offset<Double> within() {
        return Offset.offset(1e-6);
    }
}
