package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;

/** 원문을 재전송하지 않고 생성 출력의 수정 조건만 전달한다. */
public enum GenerationOutputRetry {
    COMPACT_STRUCTURE(
            "API 스키마의 text와 mascot_hint 두 필드만 출력한다. 식별자·전략·지원·response_type은 서버가 구성하므로 추가하지 않는다. 실제 대사는 character_response_contract와 길이·역할·질문 수를 지킨다."),
    RESPONSE_CONTRACT_CONFLICT(
            "character_response_contract의 목표·적용 지원·expected_response_type을 따른다. S3 학습 전략은 ANSWER_REVEAL로 쓰고 실제 해당 목표의 답을 캐릭터의 올바른 역할로 표현한다. 유형만 고쳐서 검사를 통과하려 하지 않는다."),
    INVALID_STRUCTURE("필수 필드·분석의 식별자와 전략·길이·질문 수를 지키는 JSON 객체 하나를 출력한다.");

    private final String correction;

    GenerationOutputRetry(String correction) {
        this.correction = correction;
    }

    public String correction() {
        return correction;
    }

    public static GenerationOutputRetry from(InvalidLlmOutputException failure) {
        if ("간결 캐릭터 출력이 요청 스키마와 다릅니다.".equals(failure.getMessage())) return COMPACT_STRUCTURE;
        return "응답 유형이 캐릭터 지원 계약과 다릅니다.".equals(failure.getMessage())
                ? RESPONSE_CONTRACT_CONFLICT
                : INVALID_STRUCTURE;
    }
}
