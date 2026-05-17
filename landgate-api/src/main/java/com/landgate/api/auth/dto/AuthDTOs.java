package com.landgate.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDTOs {
    private AuthDTOs() {}

    public record RegisterRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Invalid email format")
            String email,

            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
            String password,

            @NotBlank(message = "Captcha token is required")
            String captchaToken,

            @Size(max = 100, message = "Username must be at most 100 characters")
            String username
    ) {}

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Invalid email format")
            String email,

            @NotBlank(message = "Password is required")
            String password
    ) {}
    public record CreateApiKeyRequest(String name, Long groupId) {}
}
