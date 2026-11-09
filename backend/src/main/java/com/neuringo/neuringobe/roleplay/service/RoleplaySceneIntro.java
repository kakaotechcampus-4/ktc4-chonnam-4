package com.neuringo.neuringobe.roleplay.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * 역할극 첫 대사 앞에 깡총이가 하는 장면 소개.
 *
 * <p>아동은 상대가 누구인지 모른 채 첫 대사를 듣게 되므로, 역할극 밖 도우미인 깡총이가 상대만 먼저 알려 준다. 첫 목표가 "무슨 일이 있었는지 알아차리기"라서
 * 사건·감정은 말하지 않는다. 강사가 정한 이름표가 "넘어진 친구 지우"처럼 사건을 담을 수 있으므로 마지막 낱말(이름이나 관계)만 부른다.
 */
final class RoleplaySceneIntro {
    static final String PURPOSE = "INTRO";
    private static final int MAX_NAME_LENGTH = 10;
    private static final String FALLBACK_NAME = "친구";

    private RoleplaySceneIntro() {}

    /** 이름표가 없으면(안내자 시나리오 등) 소개하지 않는다. */
    static Optional<String> text(String role) {
        if (role == null || role.isBlank()) return Optional.empty();
        String name = callName(role);
        return Optional.of("저기 " + name + " 보이지? " + name + " 말을 잘 듣고 이야기를 나눠 보자.");
    }

    /** 첫 질문 메시지에 묶어 복원·재전송 때도 같은 ID 를 돌려준다. */
    static UUID messageId(UUID openingMessageId) {
        return UUID.nameUUIDFromBytes(
                ("roleplay-scene-intro:" + openingMessageId).getBytes(StandardCharsets.UTF_8));
    }

    static String callName(String role) {
        String plain = role.replaceAll("\\([^)]*\\)", " ").trim();
        String[] words = plain.split("\\s+");
        String last = words.length == 0 ? "" : words[words.length - 1];
        if (last.isEmpty() || last.length() > MAX_NAME_LENGTH) return FALLBACK_NAME;
        return last;
    }
}
