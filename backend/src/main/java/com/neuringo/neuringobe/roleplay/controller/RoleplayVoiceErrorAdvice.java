package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.common.ApiErrorResponse;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.TraceIds;
import com.neuringo.neuringobe.roleplay.application.RoleplayContextAssembler;
import com.neuringo.neuringobe.roleplay.infrastructure.input.RoleplayUploadSpoolFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes = RoleplayVoiceController.class)
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
public class RoleplayVoiceErrorAdvice {
    @ExceptionHandler(RoleplayUploadSpoolFactory.Rejected.class)
    public ResponseEntity<ApiErrorResponse> upload(
            RoleplayUploadSpoolFactory.Rejected ex, HttpServletRequest request) {
        var error =
                switch (ex.reason()) {
                    case EMPTY ->
                            new ApiException(
                                    HttpStatus.BAD_REQUEST,
                                    "VOICE_UPLOAD_EMPTY",
                                    "음성 파일이 비어 있습니다.");
                    case TOO_LARGE ->
                            new ApiException(
                                    HttpStatus.CONTENT_TOO_LARGE,
                                    "VOICE_UPLOAD_TOO_LARGE",
                                    "음성 파일 크기가 허용 범위를 초과했습니다.");
                    case UNSUPPORTED_FORMAT ->
                            new ApiException(
                                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                    "VOICE_UPLOAD_FORMAT",
                                    "지원하지 않는 음성 형식입니다.");
                };
        return response(error, request);
    }

    @ExceptionHandler(RoleplayContextAssembler.Unavailable.class)
    public ResponseEntity<ApiErrorResponse> context(
            RoleplayContextAssembler.Unavailable ex, HttpServletRequest request) {
        return response(
                new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "ROLEPLAY_CONTEXT_UNAVAILABLE",
                        "역할극 입력 서비스를 사용할 수 없습니다."),
                request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            ApiException error, HttpServletRequest request) {
        return ResponseEntity.status(error.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiErrorResponse.of(
                                error.getStatus().value(),
                                error.getCode(),
                                error.getMessage(),
                                request.getRequestURI(),
                                TraceIds.newTraceId()));
    }
}
