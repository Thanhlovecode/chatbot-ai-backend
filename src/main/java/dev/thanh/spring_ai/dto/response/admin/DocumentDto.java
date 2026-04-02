package dev.thanh.spring_ai.dto.response.admin;

import dev.thanh.spring_ai.enums.DocumentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class DocumentDto {
    private UUID id;
    private String fileName;
    private Long fileSize;
    private Integer chunkCount;
    private String topic;
    private DocumentStatus status;
    private LocalDateTime uploadedAt;
}
