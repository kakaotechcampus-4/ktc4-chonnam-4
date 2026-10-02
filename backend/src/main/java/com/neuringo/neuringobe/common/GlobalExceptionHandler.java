package com.neuringo.neuringobe.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Controller 처리 중 발생한 예외를 {@link ApiErrorResponse} 형식으로 응답한다. Spring 표준 예외는 {@link
 * ResponseEntityExceptionHandler} 가 정한 상태 코드·헤더를 유지한 채 본문만 바꾼다. 응답이 이미 전송되기 시작한 경우는 제외한다.
 *
 * <p>Controller 밖(Security 필터)의 오류는 여기 닿지 않는다. 401·403 은 {@code ApiSecurityFailureHandler} 가, 그 밖의
 * 필터 단계 예외는 {@link ApiErrorController} 가 응답한다.
 *
 * <p>Spring·DB 등 외부에서 온 예외의 메시지는 응답과 로그에 쓰지 않는다. 요청 값(아동 이름 등)이 섞일 수 있기 때문이다. 직접 정의한 {@link
 * ApiException} 의 메시지는 문구를 통제하고 식별자만 담으므로 응답에 그대로 쓴다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException ex, HttpServletRequest request, HttpServletResponse response) {
        if (isCommitted(response, ex)) {
            return null;
        }

        return ResponseEntity.status(ex.getStatus())
                .body(
                        ApiErrorResponse.of(
                                ex.getStatus().value(),
                                ex.getCode(),
                                ex.getMessage(),
                                request.getRequestURI(),
                                TraceIds.newTraceId()));
    }

    // 본문만 만들고 응답은 handleExceptionInternal 에 맡긴다(응답 전송 여부 확인을 한곳에서 하기 위함).
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        new ApiErrorResponse.FieldError(
                                                error.getField(), error.getDefaultMessage()))
                        .toList();

        ApiErrorResponse body =
                ApiErrorResponse.of(
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        "VALIDATION_FAILED",
                        "요청 값이 올바르지 않습니다.",
                        pathOf(request),
                        TraceIds.newTraceId(),
                        fieldErrors);

        return handleExceptionInternal(
                ex, body, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    // Spring 표준 예외(잘못된 UUID 400, 없는 경로 404, 405, 415 등)는 부모가 상태 코드를 정한 뒤 여기로 온다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest
                && isCommitted(servletWebRequest.getResponse(), ex)) {
            return null;
        }

        ApiErrorResponse errorBody;
        if (body instanceof ApiErrorResponse apiErrorResponse) {
            errorBody = apiErrorResponse;
        } else {
            errorBody =
                    ApiErrorResponse.of(
                            statusCode.value(),
                            StatusErrors.codeOf(statusCode),
                            StatusErrors.messageOf(statusCode),
                            pathOf(request),
                            TraceIds.newTraceId());
        }

        if (statusCode.is5xxServerError()) {
            logServerError(errorBody.error().traceId(), ex);
        }

        return ResponseEntity.status(statusCode).headers(headers).body(errorBody);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request, HttpServletResponse response) {
        if (isCommitted(response, ex)) {
            return null;
        }

        String traceId = TraceIds.newTraceId();
        logServerError(traceId, ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ApiErrorResponse.of(
                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                "INTERNAL_ERROR",
                                StatusErrors.messageOf(HttpStatus.INTERNAL_SERVER_ERROR),
                                request.getRequestURI(),
                                traceId));
    }

    // 응답 일부가 이미 전송됐으면 오류 응답을 새로 쓸 수 없으므로 건너뛴다.
    // 부모 구현은 예외 전체를 로그에 남기지만, 여기서는 예외 타입만 남긴다.
    private boolean isCommitted(HttpServletResponse response, Exception ex) {
        if (response != null && response.isCommitted()) {
            log.warn(
                    "response already committed, error response skipped type={}",
                    ex.getClass().getName());
            return true;
        }
        return false;
    }

    // 예외 메시지·cause·스택은 남기지 않는다(클래스 설명 참고). 응답과 같은 traceId 로 추적한다.
    private void logServerError(String traceId, Exception ex) {
        log.error("unhandled server error traceId={} type={}", traceId, ex.getClass().getName());
    }

    private String pathOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return null;
    }
}
