package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpProperties;
import com.neuringo.neuringobe.roleplay.dto.RoleplayTurnResponse;
import com.neuringo.neuringobe.roleplay.service.RoleplayVoiceInputService;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnProperty(name = "roleplay.http.enabled", havingValue = "true")
@RequestMapping("/api/v1/roleplay-sessions")
public class RoleplayVoiceController {
    private final RoleplayVoiceInputService service;
    private final Duration budget;

    public RoleplayVoiceController(
            RoleplayVoiceInputService service, RoleplayHttpProperties properties) {
        this.service = service;
        this.budget = properties.turnBudget();
    }

    @PostMapping(
            value = "/{sessionId}/voice-inputs",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RoleplayTurnResponse>> submit(
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestPart("audio") MultipartFile audio,
            Authentication authentication)
            throws IOException {
        var deadline = RoleplayTurnDeadline.start(budget);
        var response =
                service.submitVoice(
                        authentication,
                        sessionId,
                        key,
                        audio.getInputStream(),
                        audio.getContentType(),
                        deadline);
        HttpStatus status =
                switch (response.status()) {
                    case CONFLICT ->
                            throw new ApiException(
                                    HttpStatus.CONFLICT,
                                    "ROLEPLAY_CONFLICT",
                                    "요청이 현재 역할극 상태와 일치하지 않습니다.");
                    case BUSY ->
                            throw new ApiException(
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "ROLEPLAY_BUSY",
                                    "역할극 입력을 처리할 수 없습니다. 같은 요청으로 다시 시도해 주세요.");
                    case PROCESSING -> HttpStatus.ACCEPTED;
                    case DELIVERED, REINPUT_REQUIRED, RETRY_REQUIRED, STOPPED, NOTICE_UNAVAILABLE ->
                            HttpStatus.OK;
                };
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(response));
    }
}
