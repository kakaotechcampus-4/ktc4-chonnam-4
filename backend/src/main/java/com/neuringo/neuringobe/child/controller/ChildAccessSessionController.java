package com.neuringo.neuringobe.child.controller;

import com.neuringo.neuringobe.child.dto.ChildAccessSessionRequest;
import com.neuringo.neuringobe.child.dto.ChildAccessSessionResponse;
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

    private final ChildAccessCodeService service;
    private final HttpSessionSecurityContextRepository contextRepository;

    public ChildAccessSessionController(
            ChildAccessCodeService service,
            HttpSessionSecurityContextRepository contextRepository) {
        this.service = service;
        this.contextRepository = contextRepository;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChildAccessSessionResponse>> enter(
            @RequestBody @Valid ChildAccessSessionRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        ChildAccessSessionResponse child = service.enter(request.accessCode());
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
