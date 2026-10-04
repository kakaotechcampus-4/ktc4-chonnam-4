package com.neuringo.neuringobe.user.controller;

import com.neuringo.neuringobe.common.ApiResponse;
import com.neuringo.neuringobe.user.dto.SignupRequest;
import com.neuringo.neuringobe.user.dto.UserResponse;
import com.neuringo.neuringobe.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<UserResponse> signup(@RequestBody @Valid SignupRequest request) {
        return ApiResponse.of(userService.signup(request));
    }
}
