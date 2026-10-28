package com.neuringo.neuringobe.quiz.controller;

import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.quiz.domain.InitialDifficultyPolicy;
import com.neuringo.neuringobe.quiz.domain.InitialDifficultyPolicy.CalculationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = QuizController.class)
public class QuizExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(QuizExceptionHandler.class);
    private static final String ERROR_CODE = "QUIZ_RESULT_CALCULATION_FAILED";

    @ExceptionHandler(CalculationFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleCalculationFailure(
            CalculationFailedException failure, HttpServletRequest request) {
        String traceId = TraceIds.newTraceId();
        log.error(
                "퀴즈 난이도 계산 실패 code={} attempts={} policyVersion={} traceId={}",
                ERROR_CODE,
                failure.attempts(),
                InitialDifficultyPolicy.VERSION,
                traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ApiErrorResponse.of(
                                500,
                                ERROR_CODE,
                                "퀴즈 결과를 계산하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                                request.getRequestURI(),
                                traceId));
    }
}
