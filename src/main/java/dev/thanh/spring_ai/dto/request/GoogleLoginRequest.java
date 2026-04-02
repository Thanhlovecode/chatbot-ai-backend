package dev.thanh.spring_ai.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @NotBlank(message = "ID token is required")
        String idToken
) {
}
