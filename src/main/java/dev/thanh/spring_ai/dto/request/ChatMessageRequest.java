package dev.thanh.spring_ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        @NotBlank(message = "Message cannot be blank")
        @Size(max = 10000, message = "Message must not exceed 10000 characters")
        String message,
        String sessionId
) {
}
