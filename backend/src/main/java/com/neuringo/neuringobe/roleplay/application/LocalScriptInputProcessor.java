package com.neuringo.neuringobe.roleplay.application;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayInputProcessor;

/**
 * 로컬 끝까지 돌려 보기 전용 입력 처리(roleplay.local-script-input=true). 대본 STT 가 돌려준 합성 답의 공백만 정리하고 길이를 본다.
 *
 * <p>운영 입력 처리기(정규화·개인정보 거르기·안전 판단)는 팀 코드에 아직 없다. 이 클래스는 그 일을 하지 않으므로 실제 아동 음성에는 쓰지 않는다. 설정을 주지 않으면
 * 빈이 생기지 않아 지금처럼 역할극 HTTP API 를 켤 수 없다.
 */
public final class LocalScriptInputProcessor implements RoleplayInputProcessor {
    static final int MAX_CHARS = 200;

    @Override
    public Result process(String transcription) {
        if (transcription == null) {
            return new Invalid();
        }
        String text = transcription.strip().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > MAX_CHARS) {
            return new Invalid();
        }
        return new Accepted(text);
    }
}
