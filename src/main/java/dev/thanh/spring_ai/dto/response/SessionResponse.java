package dev.thanh.spring_ai.dto.response;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record SessionResponse(
        String id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
