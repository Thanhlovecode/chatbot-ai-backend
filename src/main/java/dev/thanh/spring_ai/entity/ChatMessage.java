package dev.thanh.spring_ai.entity;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.thanh.spring_ai.enums.MessageRole;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ChatMessage extends BaseEntity<UUID> {

    @Id
    @Column(columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "message_id", nullable = false, unique = true, length = 50)
    private String messageId;

    @Column(name = "session_id", columnDefinition = "UUID",
            nullable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "model", length = 50)
    private String model;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UuidCreator.getTimeOrderedEpoch();
        }
    }
}