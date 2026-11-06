package com.neuringo.neuringobe.report.application.port;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 학급의 학습 목표 가운데 뜻이 가까운 목표를 찾는다. 확장 추천의 다음 목표 후보를 고를 때 쓴다.
 *
 * <p>구현은 바꿔 끼운다. 지금은 메모리 구현(InMemoryGoalSimilaritySearch)만 있고, 운영 구현은 임베딩 모델과 벡터 저장소(pgvector)로 같은
 * 계약을 지킨다. 담당 강사의 학급 밖 목표는 돌려주지 않는다.
 */
public interface GoalSimilaritySearch {

    /**
     * @param excludedGoalIds 이미 한 목표·지금 목표처럼 후보에서 뺄 목표
     * @return 유사도가 높은 순서. 최대 limit 개
     */
    List<SimilarGoal> findSimilar(
            UUID classId, String queryText, Set<UUID> excludedGoalIds, int limit);
}
