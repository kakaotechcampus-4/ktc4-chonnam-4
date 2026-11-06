package com.neuringo.neuringobe.ai.eval.sim;

import com.neuringo.neuringobe.report.application.similarity.TextEmbedder;

/** 글자 두 개씩 묶어 해시한 벡터. 임베딩 API 없이 같은 결과를 내려고 쓰는 시뮬레이션용 대체품이다. 겹치는 낱말이 많을수록 비슷하게 나오지만 뜻은 모른다. */
final class CharBigramEmbedder implements TextEmbedder {

    private static final int DIMENSIONS = 512;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        String compact = text.replaceAll("\\s+", "");
        for (int i = 0; i + 1 < compact.length(); i++) {
            int bucket = Math.floorMod(compact.substring(i, i + 2).hashCode(), DIMENSIONS);
            vector[bucket] += 1f;
        }
        return vector;
    }
}
