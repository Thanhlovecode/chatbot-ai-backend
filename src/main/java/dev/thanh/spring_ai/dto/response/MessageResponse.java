package dev.thanh.spring_ai.dto.response;

import dev.thanh.spring_ai.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse implements Serializable {
    private String sessionId;
    private MessageRole role;
    private String content;
    private String model;
    private LocalDateTime createdAt;
}

