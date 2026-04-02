package dev.thanh.spring_ai.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.thanh.spring_ai.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    private String id;
    private String sessionId;   
    private MessageRole role;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime createdAt;

    public Message toSpringAiMessage() {
        return switch (role) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            default -> throw new IllegalArgumentException("Unsupported role: " + role);
        };
    }
}
