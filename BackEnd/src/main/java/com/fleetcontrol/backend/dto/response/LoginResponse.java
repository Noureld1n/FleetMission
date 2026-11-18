package com.fleetcontrol.backend.dto.response;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {}
