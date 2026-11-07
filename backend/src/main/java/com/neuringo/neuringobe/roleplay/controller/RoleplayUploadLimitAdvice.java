package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.roleplay.service.RoleplayUnidentifiedUploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Order(-2)
@RestControllerAdvice
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplayUploadLimitAdvice {
    private static final Pattern VOICE_PATH =
            Pattern.compile("/api/v1/roleplay-sessions/([^/]+)/voice-inputs");
    private final RoleplayUnidentifiedUploadService uploads;

    public RoleplayUploadLimitAdvice(RoleplayUnidentifiedUploadService uploads) {
        this.uploads = uploads;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> uploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var match = VOICE_PATH.matcher(path);
        if (!"POST".equals(request.getMethod()) || !match.matches()) {
            throw exception;
        }
        UUID sessionId;
        UUID key;
        try {
            sessionId = UUID.fromString(match.group(1));
            var header = request.getHeader("Idempotency-Key");
            if (header == null) throw new IllegalArgumentException();
            key = UUID.fromString(header);
        } catch (IllegalArgumentException invalid) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", request);
        }
        try {
            return RoleplayVoiceController.response(
                    uploads.record(
                            SecurityContextHolder.getContext().getAuthentication(),
                            sessionId,
                            key,
                            RoleplayUploadDeadlineFilter.deadline(request)));
        } catch (ApiException api) {
            return error(api.getStatus(), api.getCode(), api.getMessage(), request);
        }
    }

    private ResponseEntity<ApiErrorResponse> error(
            org.springframework.http.HttpStatusCode status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiErrorResponse.of(
                                status.value(),
                                code,
                                message,
                                request.getRequestURI(),
                                TraceIds.newTraceId()));
    }
}
