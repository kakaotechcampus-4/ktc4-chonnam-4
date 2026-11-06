package com.neuringo.neuringobe.report.domain;

/** 0~1 로 정규화한 지표 하나. 분자·분모를 함께 들고 있어서 화면에 "2/3" 처럼 근거를 같이 보여 줄 수 있다. */
public record Ratio(int numerator, int denominator) {

    public Ratio {
        if (denominator <= 0 || numerator < 0 || numerator > denominator) {
            throw new IllegalArgumentException(
                    "ratio must be within 0..1 with a positive denominator");
        }
    }

    /** 분모가 0 이면 잴 수 없는 지표라 null 이다. */
    static Ratio ofOrNull(int numerator, int denominator) {
        return denominator == 0 ? null : new Ratio(numerator, denominator);
    }

    public double value() {
        return (double) numerator / denominator;
    }
}
