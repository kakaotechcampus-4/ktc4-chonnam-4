package com.neuringo.neuringobe.report.application.similarity;

import java.util.Objects;

/** 코사인 유사도. 영벡터는 어느 쪽과도 0 으로 본다. 부동소수 오차로 1 을 넘지 않게 -1~1 로 자른다. */
final class CosineSimilarity {

    private CosineSimilarity() {}

    static double between(float[] left, float[] right) {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
        if (left.length != right.length) {
            throw new IllegalArgumentException("vectors must have the same dimension");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        double cosine = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return Math.max(-1.0, Math.min(1.0, cosine));
    }
}
