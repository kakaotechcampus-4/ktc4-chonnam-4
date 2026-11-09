package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.child.dto.ChildAccessSessionRequest;
import com.neuringo.neuringobe.child.dto.ChildAccessSessionResponse;
import com.neuringo.neuringobe.child.security.ChildEntryAttemptLimiter;
import com.neuringo.neuringobe.child.security.ChildPrincipal;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/child-access-sessions")
public class ChildAccessSessionController {

    /** 틀린 코드로 세는 오류. 그 밖의 오류(형식 422·CSRF 403·서버 500)는 코드를 맞혀 본 것이 아니므로 세지 않는다. */
    private static final Set<String> WRONG_CODE_ERRORS =
            Set.of("INVALID_ACCESS_CODE", "ACCESS_CODE_EXPIRED");

    private final ChildAccessCodeService service;
    private final HttpSessionSecurityContextRepository contextRepository;
    private final ChildEntryAttemptLimiter attempts;

    public ChildAccessSessionController(
            ChildAccessCodeService service,
            HttpSessionSecurityContextRepository contextRepository,
            ChildEntryAttemptLimiter attempts) {
        this.service = service;
        this.contextRepository = contextRepository;
        this.attempts = attempts;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChildAccessSessionResponse>> enter(
            @RequestBody @Valid ChildAccessSessionRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        // 서버에서는 nginx 가 X-Forwarded-For 로 넘긴 접속 IP 를 Tomcat 이 getRemoteAddr 로 바꿔 준다
        // (server.forward-headers-strategy: native). 막힌 IP 는 맞는 코드도 받지 않는다.
        String client = servletRequest.getRemoteAddr();
        attempts.checkAllowed(client);
        ChildAccessSessionResponse child;
        try {
            child = service.enter(request.accessCode());
        } catch (ApiException ex) {
            if (WRONG_CODE_ERRORS.contains(ex.getCode())) {
                attempts.recordFailure(client);
            }
            throw ex;
        }
        HttpSession previous = servletRequest.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = servletRequest.getSession(true);
        session.setMaxInactiveInterval(-1);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ChildPrincipal(child.childId()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CHILD"))));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.created(URI.create("/api/v1/child-access-sessions/current"))
                .body(ApiResponse.of(child));
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> exit(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ChildPrincipal)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "아동 입장이 필요합니다.");
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
}
