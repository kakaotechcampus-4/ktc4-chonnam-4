package com.neuringo.neuringobe.report.application.similarity;

/** 문장을 벡터로 바꾼다. 운영에서는 임베딩 모델(예: text-embedding-3-small) 어댑터가 구현한다. 같은 색인 안에서는 차원이 같아야 한다. */
@FunctionalInterface
public interface TextEmbedder {

    float[] embed(String text);
}
