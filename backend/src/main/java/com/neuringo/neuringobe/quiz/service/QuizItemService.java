package com.neuringo.neuringobe.quiz.service;

import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.quiz.domain.QuizItem;
import com.neuringo.neuringobe.quiz.dto.CreateQuizItemRequest;
import com.neuringo.neuringobe.quiz.dto.QuizItemResponse;
import com.neuringo.neuringobe.quiz.repository.QuizItemRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuizItemService {
    private final QuizItemRepository items;

    public QuizItemService(QuizItemRepository items) {
        this.items = items;
    }

    @Transactional
    public QuizItemResponse create(CreateQuizItemRequest request, Authentication authentication) {
        requireInstructor(authentication);
        try {
            QuizItem item =
                    new QuizItem(
                            UUID.randomUUID(),
                            request.quizType(),
                            request.emotion(),
                            request.questionText(),
                            request.imageUrl(),
                            request.choices(),
                            request.correctAnswer(),
                            request.acceptableAnswers() == null
                                    ? List.of()
                                    : request.acceptableAnswers(),
                            request.hints() == null ? List.of() : request.hints(),
                            1);
            return QuizItemResponse.from(items.save(item));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_QUIZ_ITEM", "퀴즈 문항 구성이 올바르지 않습니다.");
        }
    }

    public List<QuizItemResponse> list(Authentication authentication) {
        requireInstructor(authentication);
        return items.findAll().stream().map(QuizItemResponse::from).toList();
    }

    public QuizItemResponse get(UUID itemId, Authentication authentication) {
        requireInstructor(authentication);
        return QuizItemResponse.from(
                items.findById(itemId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "QUIZ_ITEM_NOT_FOUND", "퀴즈 문항을 찾을 수 없습니다.")));
    }

    private void requireInstructor(Authentication authentication) {
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(
                                authority -> "ROLE_INSTRUCTOR".equals(authority.getAuthority()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "강사만 문항을 관리할 수 있습니다.");
        }
    }
}
