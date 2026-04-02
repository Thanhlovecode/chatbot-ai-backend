package dev.thanh.spring_ai.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Counts AI prompt input tokens using JTokkit (cl100k_base encoding).
 * <p>
 * Uses cl100k_base which is compatible with GPT-4/Gemini tokenization
 * (~5% margin of error vs Gemini's native tokenizer — acceptable for quota checks).
 * <p>
 * Thread-safe: the Encoding instance is stateless and reusable.
 */
@Service
@Slf4j(topic = "TOKEN-COUNTER")
public class TokenCounterService {

    // cl100k_base is the encoding used by GPT-4 / text-embedding-ada-002
    // Compatible approximation for Gemini models
    private final Encoding encoding;

    public TokenCounterService() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        log.info("✅ TokenCounterService initialized with cl100k_base encoding");
    }

    /**
     * Count input tokens for the full prompt sent to the LLM:
     * system prompt + RAG context + conversation history + user question.
     *
     * @param systemPromptText system prompt template text (with RAG filled in)
     * @param history          previous conversation messages
     * @param userQuestion     the current user question
     * @return estimated total input token count
     */
    public int countInputTokens(String systemPromptText, List<Message> history, String userQuestion) {
        StringBuilder sb = new StringBuilder();

        // System prompt (includes RAG context already injected)
        if (systemPromptText != null && !systemPromptText.isBlank()) {
            sb.append(systemPromptText).append('\n');
        }

        // Conversation history
        if (history != null) {
            for (Message msg : history) {
                sb.append(msg.getMessageType().name())
                  .append(": ")
                  .append(msg.getText())
                  .append('\n');
            }
        }

        // Current user question
        if (userQuestion != null && !userQuestion.isBlank()) {
            sb.append("user: ").append(userQuestion);
        }

        int tokenCount = encoding.countTokens(sb.toString());
        log.debug("Input token count: {} (history={}, questionLen={})",
                tokenCount, history == null ? 0 : history.size(),
                userQuestion == null ? 0 : userQuestion.length());

        return tokenCount;
    }
}
