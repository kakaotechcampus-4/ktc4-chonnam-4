package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.quiz.repository.ActivityQuizRepository;
import com.neuringo.neuringobe.quiz.repository.QuizAttemptRepository;
import com.neuringo.neuringobe.quiz.repository.QuizHintRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아동의 퀴즈 기록을 지운다. 퀴즈 테이블끼리 FK 가 이어져 있어 지우는 순서(힌트 → 응답 → 결과 → 배정 문항)를 퀴즈 도메인 안에 둔다. 각 쿼리는 activity 를
 * 거쳐 아동을 찾으므로 활동 행을 지우기 전에 불러야 한다.
 */
@Service
public class QuizDeletionService {

    private final ActivityQuizRepository activityQuizzes;
    private final QuizAttemptRepository attempts;
    private final QuizHintRepository hints;
    private final QuizResultRepository results;

    public QuizDeletionService(
            ActivityQuizRepository activityQuizzes,
            QuizAttemptRepository attempts,
            QuizHintRepository hints,
            QuizResultRepository results) {
        this.activityQuizzes = activityQuizzes;
        this.attempts = attempts;
        this.hints = hints;
        this.results = results;
    }

    /** 부르는 쪽이 활동·아동 행을 잠근 트랜잭션 안에서 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByChildIds(Collection<UUID> childIds) {
        hints.deleteByChildIds(childIds);
        attempts.deleteByChildIds(childIds);
        results.deleteByChildIds(childIds);
        activityQuizzes.deleteByChildIds(childIds);
    }
}
