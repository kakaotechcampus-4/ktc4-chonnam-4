package com.neuringo.neuringobe.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 최초 퀴즈 문항 선택(ADR 2026-10-03 D4). 유형 순서로 하나씩, 같은 유형 안에서는 무작위다. */
@ExtendWith(MockitoExtension.class)
class QuizAssignmentServiceTest {

    @Mock private QuizItemRepository items;

    @InjectMocks private QuizAssignmentService service;

    @Test
    void picksOneApprovedItemPerTypeInTypeOrderAndSkipsEmptyTypes() {
        QuizItem self = item(QuizType.SELF_EMOTION_SITUATION);
        QuizItem image = item(QuizType.OTHER_EMOTION_IMAGE);
        given(items.findByQuizTypeAndStatus(QuizType.SELF_EMOTION_SITUATION, "APPROVED"))
                .willReturn(List.of(self));
        given(items.findByQuizTypeAndStatus(QuizType.OTHER_EMOTION_SITUATION, "APPROVED"))
                .willReturn(List.of());
        given(items.findByQuizTypeAndStatus(QuizType.OTHER_EMOTION_IMAGE, "APPROVED"))
                .willReturn(List.of(image));

        assertThat(service.pickInitialItems()).containsExactly(self, image);
    }

    @Test
    void picksAmongApprovedItemsOfTheSameTypeAtRandom() {
        QuizItem first = item(QuizType.SELF_EMOTION_SITUATION);
        QuizItem second = item(QuizType.SELF_EMOTION_SITUATION);
        given(items.findByQuizTypeAndStatus(QuizType.SELF_EMOTION_SITUATION, "APPROVED"))
                .willReturn(List.of(first, second));
        given(items.findByQuizTypeAndStatus(QuizType.OTHER_EMOTION_SITUATION, "APPROVED"))
                .willReturn(List.of());
        given(items.findByQuizTypeAndStatus(QuizType.OTHER_EMOTION_IMAGE, "APPROVED"))
                .willReturn(List.of());

        Set<UUID> picked = new HashSet<>();
        // 둘 중 하나만 계속 나올 확률은 2^-63 이다.
        for (int i = 0; i < 64; i++) {
            picked.add(service.pickInitialItems().getFirst().getItemId());
        }

        assertThat(picked).containsExactlyInAnyOrder(first.getItemId(), second.getItemId());
    }

    private static QuizItem item(QuizType type) {
        return new QuizItem(
                UUID.randomUUID(),
                type,
                Emotion.JOY,
                "어떤 기분일까?",
                type == QuizType.OTHER_EMOTION_IMAGE ? "/quiz-images/sample-anger.svg" : null,
                List.of("기쁨", "슬픔"),
                "기쁨",
                List.of("기쁨"),
                List.of(),
                1);
    }
}
