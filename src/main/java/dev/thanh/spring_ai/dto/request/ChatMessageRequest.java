package dev.thanh.spring_ai.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChatMessageRequest(
        @NotBlank(message = "Message cannot be blank")
        String message,
        String sessionId
) {
}
