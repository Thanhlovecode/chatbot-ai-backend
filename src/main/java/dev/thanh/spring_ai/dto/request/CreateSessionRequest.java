package dev.thanh.spring_ai.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank(message = "User ID cannot be blank")
        String userId,

        String title
) {
}

