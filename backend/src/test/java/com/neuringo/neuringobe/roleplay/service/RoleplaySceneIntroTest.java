package com.neuringo.neuringobe.roleplay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoleplaySceneIntroTest {

    @Test
    void introducesTheCharacterByItsName() {
        assertThat(RoleplaySceneIntro.text("민수")).isEqualTo("저기 민수 보이지? 민수 말을 잘 듣고 이야기를 나눠 보자.");
    }

    @Test
    void callsOnlyTheLastWordSoTheRoleDoesNotRevealTheEvent() {
        assertThat(RoleplaySceneIntro.callName("넘어진 친구 지우")).isEqualTo("지우");
        assertThat(RoleplaySceneIntro.callName("같은 반 친구")).isEqualTo("친구");
        assertThat(RoleplaySceneIntro.callName("하은(친구)")).isEqualTo("하은");
    }

    @Test
    void fallsBackToFriendForUnusableNames() {
        assertThat(RoleplaySceneIntro.callName("(친구)")).isEqualTo("친구");
        assertThat(RoleplaySceneIntro.callName("아주아주아주아주긴이름표입니다")).isEqualTo("친구");
    }

    @Test
    void namesTheCharacterOnlyWhenTheScenarioHasOne() {
        assertThat(RoleplaySceneIntro.name("넘어진 친구 지우")).contains("지우");
        assertThat(RoleplaySceneIntro.name(null)).isEmpty();
        assertThat(RoleplaySceneIntro.name("  ")).isEmpty();
    }

    @Test
    void messageIdIsStableForTheSameOpening() {
        var opening = UUID.randomUUID();
        assertThat(RoleplaySceneIntro.messageId(opening))
                .isEqualTo(RoleplaySceneIntro.messageId(opening))
                .isNotEqualTo(opening);
    }
}
