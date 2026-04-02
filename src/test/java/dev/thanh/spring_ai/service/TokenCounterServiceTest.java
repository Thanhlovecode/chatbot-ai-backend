package dev.thanh.spring_ai.service;

import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenCounterService — Unit Tests")
class TokenCounterServiceTest {

    private TokenCounterService tokenCounterService;

    @BeforeEach
    void setUp() {
        tokenCounterService = new TokenCounterService();
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("countInputTokens — full prompt — should return positive count")
    void countInputTokens_WithFullPrompt_ShouldReturnPositiveCount() {
        // Given
        String systemPrompt = "You are a helpful assistant. Context: Java Spring Boot is awesome.";
        List<Message> history = List.of(
                new UserMessage("What is Spring Boot?"),
                new AssistantMessage("Spring Boot is an opinionated framework.")
        );
        String userQuestion = "Can you explain dependency injection?";

        // When
        int count = tokenCounterService.countInputTokens(systemPrompt, history, userQuestion);

        // Then
        assertThat(count).isPositive();
    }

    // ─────────────────────────────────────────────────────────
    // Edge Cases
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("countInputTokens — null systemPrompt — should count only history + question")
    void countInputTokens_WhenSystemPromptNull_ShouldCountOnlyOtherParts() {
        // Given
        List<Message> history = List.of(new UserMessage("Hello"));
        String userQuestion = "How are you?";

        // When
        int count = tokenCounterService.countInputTokens(null, history, userQuestion);

        // Then
        assertThat(count).isPositive();
    }

    @Test
    @DisplayName("countInputTokens — null history — should not throw")
    void countInputTokens_WhenHistoryNull_ShouldNotThrow() {
        // Given
        String systemPrompt = "Context: Some info.";
        String userQuestion = "Question here.";

        // When
        int count = tokenCounterService.countInputTokens(systemPrompt, null, userQuestion);

        // Then
        assertThat(count).isPositive();
    }

    @Test
    @DisplayName("countInputTokens — blank history — should not throw")
    void countInputTokens_WhenHistoryEmpty_ShouldNotThrow() {
        // Given
        String systemPrompt = "Context: Some info.";

        // When
        int count = tokenCounterService.countInputTokens(systemPrompt, Collections.emptyList(), "Question");

        // Then
        assertThat(count).isPositive();
    }

    @Test
    @DisplayName("countInputTokens — all blank inputs — should return zero or very low count")
    void countInputTokens_WhenAllBlankOrNull_ShouldReturnZeroOrVeryLow() {
        // Given / When
        int count = tokenCounterService.countInputTokens(null, null, null);

        // Then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("countInputTokens — longer prompt → more tokens than shorter prompt")
    void countInputTokens_LongerPrompt_ShouldReturnMoreTokens() {
        // Given
        String shortQuestion = "Hi";
        String longQuestion = "Hi, I have a very detailed question about the Spring framework and its dependency injection container. "
                + "Can you explain the lifecycle of a Spring Bean in detail, including all phases?";

        // When
        int shortCount = tokenCounterService.countInputTokens(null, null, shortQuestion);
        int longCount = tokenCounterService.countInputTokens(null, null, longQuestion);

        // Then
        assertThat(longCount).isGreaterThan(shortCount);
    }
}
