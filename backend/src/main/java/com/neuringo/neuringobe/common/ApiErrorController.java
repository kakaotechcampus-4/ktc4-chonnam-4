package com.neuringo.neuringobe.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서블릿 컨테이너가 /error 로 다시 보낸 요청을 공통 오류 형식으로 응답한다. Boot 기본 {@code BasicErrorController} 를 대신한다.
 *
 * <p>Controller 밖(Security 필터)에서 난 예외는 {@link GlobalExceptionHandler} 에 닿지 않고 컨테이너가 /error 로 보낸다.
 * 예: {@code BearerTokenFilter} 가 토큰을 조회하다 DB 연결에 실패한 경우. {@code SecurityConfig} 가 ERROR 디스패치를 열어 둬야
 * 여기까지 온다. 막혀 있으면 익명 요청으로 인가에 걸려 원래 상태와 상관없이 401 이 된다(PR #35 리뷰에서 확인).
 *
 * <p>응답의 {@code path} 는 /error 가 아니라 원래 요청 경로다. 예외 메시지는 응답과 로그에 쓰지 않는다({@link
 * GlobalExceptionHandler} 와 같은 이유).
 */
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("/error")
    public ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        // 컨테이너가 보낸 요청이 아니면(클라이언트가 /error 를 직접 부른 경우) 없는 경로로 본다.
        if (!(statusAttribute instanceof Integer statusValue)) {
            return response(HttpStatus.NOT_FOUND, request.getRequestURI(), TraceIds.newTraceId());
        }

        HttpStatusCode status = HttpStatusCode.valueOf(statusValue);
        String traceId = TraceIds.newTraceId();
        if (status.is5xxServerError()) {
            Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            log.error(
                    "unhandled server error traceId={} type={}",
                    traceId,
                    exception != null ? exception.getClass().getName() : "none");
        }

        return response(
                status,
                (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                traceId);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status, String path, String traceId) {
        return ResponseEntity.status(status)
                .body(
                        ApiErrorResponse.of(
                                status.value(),
                                StatusErrors.codeOf(status),
                                StatusErrors.messageOf(status),
                                path,
                                traceId));
    }
}
