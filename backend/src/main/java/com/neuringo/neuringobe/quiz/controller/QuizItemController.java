package com.neuringo.neuringobe.quiz.controller;

import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.quiz.dto.CreateQuizItemRequest;
import com.neuringo.neuringobe.quiz.dto.QuizItemResponse;
import com.neuringo.neuringobe.quiz.service.QuizItemService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quiz-items")
public class QuizItemController {
    private final QuizItemService service;

    public QuizItemController(QuizItemService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<QuizItemResponse>> create(
            @RequestBody @Valid CreateQuizItemRequest request, Authentication authentication) {
        QuizItemResponse created = service.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/quiz-items/" + created.itemId()))
                .body(ApiResponse.of(created));
    }

    @GetMapping
    public ApiResponse<List<QuizItemResponse>> list(Authentication authentication) {
        return ApiResponse.of(service.list(authentication));
    }

    @GetMapping("/{itemId}")
    public ApiResponse<QuizItemResponse> get(
            @PathVariable UUID itemId, Authentication authentication) {
        return ApiResponse.of(service.get(itemId, authentication));
    }
}
