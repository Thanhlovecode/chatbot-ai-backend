package dev.thanh.spring_ai.dto.response;

import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.enums.ResponseType;
import lombok.*;

import java.time.ZonedDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String title;
    private ZonedDateTime timestamp;
    private ResponseType type;
    private String content;
    private MessageRole role;
}

