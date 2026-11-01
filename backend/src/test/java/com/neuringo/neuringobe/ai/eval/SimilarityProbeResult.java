package com.neuringo.neuringobe.ai.eval;

import java.util.List;

/**
 * 유사도 실측 한 건. expected 는 회귀 세트가 정한 관계(self·duplicate·related·unrelated·other)이고, 기준값으로 나눈 결과(band)와
 * 맞는지 본다.
 *
 * @param suggestion RecommendationComposer 가 같은 기준값으로 고른 확장 목표. 없으면 null
 */
record SimilarityProbeResult(String query, List<Row> rows, String suggestion) {

    record Row(String title, double similarity, String expected, String band) {

        boolean matches() {
            return switch (expected) {
                case "duplicate" -> band.equals("duplicate");
                case "related" -> band.equals("related");
                case "unrelated" -> band.equals("unrelated");
                default -> true;
            };
        }
    }
}
