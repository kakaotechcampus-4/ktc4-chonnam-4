package com.neuringo.neuringobe.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.quiz.domain.Emotion;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.domain.QuizType;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 유형별 문항 선택. 구성 순서대로 붙고, 같은 유형 안에서는 무작위이며, 칸을 못 채우면 빈 목록이다. */
@ExtendWith(MockitoExtension.class)
class QuizAssignmentServiceTest {

    @Mock private QuizItemRepository items;

    @InjectMocks private QuizAssignmentService service;

    @Test
    void firstCombinedPicksOneApprovedItemPerTypeInTypeOrder() {
        QuizItem self = item(QuizType.SELF_EMOTION_SITUATION);
        QuizItem other = item(QuizType.OTHER_EMOTION_SITUATION);
        QuizItem image = item(QuizType.OTHER_EMOTION_IMAGE);
        approved(QuizType.SELF_EMOTION_SITUATION, List.of(self));
        approved(QuizType.OTHER_EMOTION_SITUATION, List.of(other));
        approved(QuizType.OTHER_EMOTION_IMAGE, List.of(image));

        assertThat(service.pickItems(ActivityType.FIRST_COMBINED))
                .containsExactly(self, other, image);
    }

    @Test
    void firstCombinedPicksNothingWhenOneTypeHasNoApprovedItem() {
        approved(QuizType.SELF_EMOTION_SITUATION, List.of(item(QuizType.SELF_EMOTION_SITUATION)));
        approved(QuizType.OTHER_EMOTION_SITUATION, List.of());

        assertThat(service.pickItems(ActivityType.FIRST_COMBINED)).isEmpty();
    }

    @Test
    void picksAmongApprovedItemsOfTheSameTypeAtRandom() {
        QuizItem first = item(QuizType.SELF_EMOTION_SITUATION);
        QuizItem second = item(QuizType.SELF_EMOTION_SITUATION);
        approved(QuizType.SELF_EMOTION_SITUATION, List.of(first, second));
        approved(QuizType.OTHER_EMOTION_SITUATION, List.of(item(QuizType.OTHER_EMOTION_SITUATION)));
        approved(QuizType.OTHER_EMOTION_IMAGE, List.of(item(QuizType.OTHER_EMOTION_IMAGE)));

        Set<UUID> picked = new HashSet<>();
        // 둘 중 하나만 계속 나올 확률은 2^-63 이다.
        for (int i = 0; i < 64; i++) {
            picked.add(service.pickItems(ActivityType.FIRST_COMBINED).getFirst().getItemId());
        }

        assertThat(picked).containsExactlyInAnyOrder(first.getItemId(), second.getItemId());
    }

    @Test
    void otherEmotionQuizPicksSevenSituationsThenThreeImagesWithoutRepeating() {
        approved(QuizType.OTHER_EMOTION_SITUATION, items(QuizType.OTHER_EMOTION_SITUATION, 8));
        approved(QuizType.OTHER_EMOTION_IMAGE, items(QuizType.OTHER_EMOTION_IMAGE, 3));

        List<QuizItem> picks = service.pickItems(ActivityType.OTHER_EMOTION_QUIZ);

        assertThat(picks).hasSize(10).doesNotHaveDuplicates();
        assertThat(picks.subList(0, 7))
                .allMatch(item -> item.getQuizType() == QuizType.OTHER_EMOTION_SITUATION);
        assertThat(picks.subList(7, 10))
                .allMatch(item -> item.getQuizType() == QuizType.OTHER_EMOTION_IMAGE);
    }

    @Test
    void selfEmotionQuizPicksNothingWhenFewerThanTenApprovedItems() {
        approved(QuizType.SELF_EMOTION_SITUATION, items(QuizType.SELF_EMOTION_SITUATION, 9));

        assertThat(service.pickItems(ActivityType.SELF_EMOTION_QUIZ)).isEmpty();
    }

    @Test
    void roleplayHasNoQuizItems() {
        assertThat(service.pickItems(ActivityType.ROLEPLAY)).isEmpty();
    }

    private void approved(QuizType type, List<QuizItem> approved) {
        given(items.findByQuizTypeAndStatus(type, "APPROVED")).willReturn(approved);
    }

    private static List<QuizItem> items(QuizType type, int count) {
        return IntStream.range(0, count).mapToObj(i -> item(type)).toList();
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
