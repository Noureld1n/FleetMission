package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.dto.request.LoginRequest;
import com.fleetcontrol.backend.dto.response.LoginResponse;
import com.fleetcontrol.backend.security.LoginRateLimiter;
import com.fleetcontrol.backend.service.LoginService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private final LoginService loginService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(LoginService loginService, LoginRateLimiter rateLimiter) {
        this.loginService = loginService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {
        return rateLimiter.check(exchange.getRequest().getRemoteAddress())
                .then(loginService.login(request));
    }
}
