package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.entity.ChatMessage;
import dev.thanh.spring_ai.enums.MessageRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessageProcessorService unit tests — covers:
 * 1. Happy path transformation (MapRecord → ChatMessage)
 * 2. Missing required fields (null sessionId, null content)
 * 3. Corrupted UUID fields (security: Redis data corruption guard)
 * 4. Role parsing (valid, invalid, null)
 * 5. Timestamp parsing (ISO-8601, epoch millis, null)
 */
@DisplayName("MessageProcessorService — Unit Tests")
class MessageProcessorServiceTest {

    private final MessageProcessorService processor = new MessageProcessorService();

    // ═══════════════════════════════════════════════════════════════════════
    // Happy Path
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transformToChatMessage — happy path")
    class HappyPathTests {

        @Test
        @DisplayName("valid record — should transform to ChatMessage with all fields")
        void validRecord_ShouldTransform() {
            UUID id = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();

            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", id.toString(),
                    "sessionId", sessionId.toString(),
                    "content", "Hello World",
                    "type", "USER",
                    "model", "gemini-2.5-flash",
                    "createdAt", "2024-03-25T10:30:00"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(id);
            assertThat(result.getSessionId()).isEqualTo(sessionId);
            assertThat(result.getContent()).isEqualTo("Hello World");
            assertThat(result.getRole()).isEqualTo(MessageRole.USER);
            assertThat(result.getModel()).isEqualTo("gemini-2.5-flash");
            assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 3, 25, 10, 30, 0));
        }

        @Test
        @DisplayName("ASSISTANT role — should map correctly")
        void assistantRole_ShouldMap() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Response from AI",
                    "type", "ASSISTANT"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getRole()).isEqualTo(MessageRole.ASSISTANT);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Missing Required Fields
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transformToChatMessage — missing required fields")
    class MissingFieldsTests {

        @Test
        @DisplayName("null sessionId — should return null (skip record)")
        void nullSessionId_ShouldReturnNull() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "content", "Hello"
            ));

            assertThat(processor.transformToChatMessage(record)).isNull();
        }

        @Test
        @DisplayName("null content — should return null (skip record)")
        void nullContent_ShouldReturnNull() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString()
            ));

            assertThat(processor.transformToChatMessage(record)).isNull();
        }

        @Test
        @DisplayName("null id — should return null (skip record)")
        void nullId_ShouldReturnNull() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello"
            ));

            assertThat(processor.transformToChatMessage(record)).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Corrupted UUID Fields (Security: Redis data corruption guard)
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transformToChatMessage — corrupted UUID")
    class CorruptedUuidTests {

        @ParameterizedTest(name = "id=\"{0}\" — should return null")
        @ValueSource(strings = {"too-short", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "", "not-a-uuid-at-all-but-has-length-36!"})
        @DisplayName("corrupted id field — should return null (length guard)")
        void corruptedId_ShouldReturnNull(String badId) {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", badId,
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello"
            ));

            assertThat(processor.transformToChatMessage(record)).isNull();
        }

        @ParameterizedTest(name = "sessionId=\"{0}\" — should return null")
        @ValueSource(strings = {"short", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
        @DisplayName("corrupted sessionId field — should return null (length guard)")
        void corruptedSessionId_ShouldReturnNull(String badSessionId) {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", badSessionId,
                    "content", "Hello"
            ));

            assertThat(processor.transformToChatMessage(record)).isNull();
        }

        @Test
        @DisplayName("valid length but invalid UUID format — should return null (catch block)")
        void validLengthInvalidFormat_ShouldReturnNull() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", "12345678-1234-1234-1234-12345678901x", // 36 chars but last is 'x'
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello"
            ));

            // UUID.fromString will throw → caught by outer catch block
            assertThat(processor.transformToChatMessage(record)).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Role Parsing
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transformToChatMessage — role parsing")
    class RoleParsingTests {

        @Test
        @DisplayName("null role — should default to USER")
        void nullRole_ShouldDefaultToUser() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello"
                    // no "type" field
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getRole()).isEqualTo(MessageRole.USER);
        }

        @Test
        @DisplayName("invalid role string — should default to USER")
        void invalidRole_ShouldDefaultToUser() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "type", "INVALID_ROLE"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getRole()).isEqualTo(MessageRole.USER);
        }

        @Test
        @DisplayName("lowercase role — should be case-insensitive")
        void lowercaseRole_ShouldWork() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "type", "assistant"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getRole()).isEqualTo(MessageRole.ASSISTANT);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Timestamp Parsing
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("transformToChatMessage — timestamp parsing")
    class TimestampParsingTests {

        @Test
        @DisplayName("ISO-8601 string — should parse correctly")
        void iso8601_ShouldParse() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "createdAt", "2024-06-15T14:30:45"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 6, 15, 14, 30, 45));
        }

        @Test
        @DisplayName("epoch millis string — should parse correctly")
        void epochMillis_ShouldParse() {
            long millis = 1711350000000L; // 2024-03-25 approx
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "createdAt", String.valueOf(millis)
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("null timestamp — should use now()")
        void nullTimestamp_ShouldUseNow() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello"
                    // no createdAt
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedAt()).isNotNull();
            // Should be close to now
            assertThat(result.getCreatedAt()).isAfter(LocalDateTime.now().minusSeconds(5));
        }

        @Test
        @DisplayName("'timestamp' field as fallback — should use it if 'createdAt' is missing")
        void timestampFallback_ShouldUseIt() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "timestamp", "2024-06-15T14:30:45"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2024, 6, 15, 14, 30, 45));
        }

        @Test
        @DisplayName("unparseable timestamp — should fallback to now()")
        void unparseableTimestamp_ShouldFallbackToNow() {
            MapRecord<String, Object, Object> record = buildRecord(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "sessionId", UUID.randomUUID().toString(),
                    "content", "Hello",
                    "createdAt", "not-a-date"
            ));

            ChatMessage result = processor.transformToChatMessage(record);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedAt()).isAfter(LocalDateTime.now().minusSeconds(5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════════════

    private MapRecord<String, Object, Object> buildRecord(Map<String, Object> fields) {
        Map<Object, Object> objFields = new HashMap<>(fields);
        return StreamRecords.mapBacked(objFields)
                .withStreamKey("chat:stream:test")
                .withId(RecordId.of("1711350000000-0"));
    }
}
