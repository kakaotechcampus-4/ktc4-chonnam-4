package com.neuringo.neuringobe.roleplay.controller;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpProperties;
import com.neuringo.neuringobe.roleplay.dto.*;
import com.neuringo.neuringobe.roleplay.service.RoleplayTextInputService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(
        name = {"roleplay.http.enabled", "roleplay.text.enabled"},
        havingValue = "true")
@RequestMapping("/api/v1/roleplay-sessions")
public class RoleplayTextController {
    private final RoleplayTextInputService service;
    private final Duration budget;

    public RoleplayTextController(
            RoleplayTextInputService service, RoleplayHttpProperties properties) {
        this.service = service;
        this.budget = properties.turnBudget();
    }

    @PostMapping(value = "/{sessionId}/text-inputs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RoleplayTurnResponse>> submit(
            @PathVariable UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RoleplayTextInputRequest input,
            Authentication authentication) {
        var deadline = RoleplayTurnDeadline.start(budget);
        return RoleplayVoiceController.response(
                service.submit(authentication, sessionId, key, input.text(), deadline));
    }
}
