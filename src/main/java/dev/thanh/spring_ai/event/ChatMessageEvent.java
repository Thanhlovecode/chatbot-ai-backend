package dev.thanh.spring_ai.event;

import dev.thanh.spring_ai.enums.MessageRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Event được publish khi có tin nhắn chat cần lưu vào DB
 */
@Getter
@Builder
public class ChatMessageEvent {

    private final String sessionId;
    private final String userId;
    private final String content;
    private final MessageRole role;
    private final String model;
    private final LocalDateTime timestamp;

    public static ChatMessageEvent userMessage(String sessionId, String userId, String content) {
        return ChatMessageEvent.builder()
                .sessionId(sessionId)
                .userId(userId)
                .content(content)
                .role(MessageRole.USER)
                .model(null)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ChatMessageEvent assistantMessage(String sessionId, String userId,
                                                     String content, String model) {
        return ChatMessageEvent.builder()
                .sessionId(sessionId)
                .userId(userId)
                .content(content)
                .role(MessageRole.ASSISTANT)
                .model(model)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
