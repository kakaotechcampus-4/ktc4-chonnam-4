package com.neuringo.neuringobe.auth.controller;

import com.neuringo.neuringobe.auth.dto.LoginRequest;
import com.neuringo.neuringobe.auth.dto.LoginResponse;
import com.neuringo.neuringobe.auth.dto.SessionResponse;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.auth.service.AuthService;
import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/sessions")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @GetMapping("/session")
    public ApiResponse<SessionResponse> current(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.of(new SessionResponse(userService.get(user.userId())));
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logout(user.sessionId());
    }
}
