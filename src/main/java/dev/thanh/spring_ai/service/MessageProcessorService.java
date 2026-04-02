package dev.thanh.spring_ai.service;


import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.enums.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;


@Slf4j
@Service
public class MessageProcessorService {

    public ChatMessage transformToChatMessage(MapRecord<String, Object, Object> record) {
        try {
            String messageId = record.getId().getValue();
            Map<Object, Object> fields = record.getValue();

            String sessionId = get(fields, "sessionId");
            String content = get(fields, "content");
            String id = get(fields, "id");

            if (sessionId == null || content == null) {
                log.warn("Invalid message {}:  missing required fields", messageId);
                return null;
            }

            return ChatMessage.builder()
                    .id(UUID.fromString(id))
                    .messageId(messageId)
                    .sessionId(UUID.fromString(sessionId))
                    .role(getRole(fields))
                    .content(content)
                    .model(get(fields, "model"))
                    .createdAt(getTimestamp(fields))
                    .build();

        } catch (Exception e) {
            log.error("Transform failed: {}", e.getMessage());
            return null;
        }
    }

    private String get(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        return value != null ?  value.toString() : null;
    }

    private MessageRole getRole(Map<Object, Object> fields) {
        try {
            String role = get(fields, "type");
            return role != null ? MessageRole.valueOf(role.toUpperCase()) : MessageRole.USER;
        } catch (IllegalArgumentException e) {
            return MessageRole.USER;
        }
    }

    private LocalDateTime getTimestamp(Map<Object, Object> fields) {
        Object value = fields.get("createdAt");
        if (value == null) {
            value = fields.get("timestamp");
        }
        
        if (value == null) return LocalDateTime.now();

        try {
            String strValue = value.toString();
            // Try parsing as ISO-8601
            try {
                return LocalDateTime.parse(strValue);
            } catch (Exception e) {
                // Ignore, try numeric
            }

            long millis = value instanceof Number n ? n.longValue() : Long.parseLong(strValue);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}, using now()", value);
            return LocalDateTime.now();
        }
    }
}