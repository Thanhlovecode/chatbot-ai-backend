package dev.thanh.spring_ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for renaming a chat session.
 */
public record RenameSessionRequest(
        @NotBlank(message = "Title must not be blank")
        @Size(max = 500, message = "Title must not exceed 500 characters")
        String title
) {
}
