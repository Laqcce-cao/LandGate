package com.landgate.api.auth.dto;

public final class AuthDTOs {
    private AuthDTOs() {}

    public record RegisterRequest(String email, String password) {}
    public record LoginRequest(String email, String password) {}
    public record CreateApiKeyRequest(String name, Long groupId) {}
}
