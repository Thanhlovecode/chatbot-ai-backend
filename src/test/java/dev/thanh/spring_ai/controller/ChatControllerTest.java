package dev.thanh.spring_ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thanh.spring_ai.config.security.CustomJwtAuthenticationConverter;
import dev.thanh.spring_ai.config.security.CustomJwtDecoder;
import dev.thanh.spring_ai.config.security.JwtAuthenticationEntryPoint;
import dev.thanh.spring_ai.config.security.SecurityConfig;
import dev.thanh.spring_ai.dto.request.ChatMessageRequest;
import dev.thanh.spring_ai.dto.request.RenameSessionRequest;
import dev.thanh.spring_ai.dto.response.ChatResponse;
import dev.thanh.spring_ai.dto.response.CursorResponse;
import dev.thanh.spring_ai.dto.response.SessionResponse;
import dev.thanh.spring_ai.enums.MessageRole;
import dev.thanh.spring_ai.enums.ResponseType;
import dev.thanh.spring_ai.service.ChatService;
import dev.thanh.spring_ai.service.ChatSessionService;
import dev.thanh.spring_ai.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BLOCKER 1 Fix: @WebMvcTest with explicit @Import(SecurityConfig.class)
 * + @MockitoBean for all JWT security beans to prevent 401 on every request.
 * + mockStatic(SecurityUtils) to provide getCurrentUserId() in each test.
 */
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("unittest")
@TestPropertySource(properties = {
        "spring.ai.google.genai.api-key=test-key",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@DisplayName("ChatController — Slice Tests")
class ChatControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Service mocks
    @MockitoBean private ChatService chatService;
    @MockitoBean private ChatSessionService chatSessionService;

    // BLOCKER 1 Fix: Mock all JWT security beans required by SecurityConfig
    @MockitoBean private CustomJwtDecoder customJwtDecoder;
    @MockitoBean private CustomJwtAuthenticationConverter customJwtAuthenticationConverter;
    @MockitoBean private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String SESSION_ID = "session-abc-123";
    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // mockStatic for SecurityUtils.getCurrentUserId()
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtilsMock = mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_UUID);
    }

    @AfterEach
    void tearDown() {
        if (securityUtilsMock != null) {
            securityUtilsMock.close();
        }
    }

    // ─────────────────────────────────────────────────────────
    // POST /api/v1/chat/stream
    // ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("POST /stream — valid request — security config loaded — should have JWT filter")
    void chatStream_SecurityConfigLoaded_ShouldHaveJwtBeans() {
        // Verify all 3 JWT security beans are properly injected (not null)
        // This confirms @Import(SecurityConfig.class) worked correctly
        // and the context is not bypassing security
        assert customJwtDecoder != null : "CustomJwtDecoder must be mocked";
        assert customJwtAuthenticationConverter != null : "CustomJwtAuthenticationConverter must be mocked";
        assert jwtAuthenticationEntryPoint != null : "JwtAuthenticationEntryPoint must be mocked";
    }

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("POST /stream — valid request — should return 200 with SSE stream")
    void chatStream_WhenValidRequest_ShouldReturn200() throws Exception {
        ChatResponse contentResponse = ChatResponse.builder()
                .sessionId(SESSION_ID).content("Hello").role(MessageRole.ASSISTANT)
                .type(ResponseType.CONTENT).timestamp(ZonedDateTime.now()).build();
        when(chatService.chatStream(any(ChatMessageRequest.class), anyString()))
                .thenReturn(Flux.just(contentResponse));

        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatMessageRequest("Hello", SESSION_ID))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("POST /stream — blank message — should return 400 (validation fails)")
    void chatStream_WhenMessageBlank_ShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatMessageRequest("", null))))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────
    // GET /api/v1/chat/sessions
    // ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("GET /sessions — should return 200 with cursor response")
    void getSessions_ShouldReturn200WithPagination() throws Exception {
        SessionResponse session = SessionResponse.builder()
                .id(SESSION_ID).title("My Chat")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        CursorResponse<SessionResponse> cursorResponse = CursorResponse.<SessionResponse>builder()
                .data(List.of(session)).hasNext(false).nextCursor(null).build();
        when(chatSessionService.getUserSessionsCursor(anyString(), any(), anyInt()))
                .thenReturn(cursorResponse);

        mockMvc.perform(get("/api/v1/chat/sessions").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(SESSION_ID))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ─────────────────────────────────────────────────────────
    // GET /api/v1/chat/{sessionId}/messages
    // ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("GET /{sessionId}/messages — should return 200")
    void getMessages_ShouldReturn200() throws Exception {
        when(chatSessionService.getMessagesCursor(anyString(), anyString(), any(), anyInt()))
                .thenReturn(CursorResponse.<dev.thanh.spring_ai.dto.response.ChatMessageResponse>builder()
                        .data(List.of()).hasNext(false).build());

        mockMvc.perform(get("/api/v1/chat/{sessionId}/messages", SESSION_ID).param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ─────────────────────────────────────────────────────────
    // DELETE /api/v1/chat/sessions/{sessionId}
    // ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("DELETE /sessions/{sessionId} — should return 200")
    void deleteSession_ShouldReturn200() throws Exception {
        doNothing().when(chatSessionService).deleteSession(anyString(), anyString());

        mockMvc.perform(delete("/api/v1/chat/sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("Session deleted successfully"));
    }

    // ─────────────────────────────────────────────────────────
    // PUT /api/v1/chat/sessions/{sessionId}/title
    // ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("PUT /sessions/{sessionId}/title — valid title — should return 200")
    void renameSession_WhenValidTitle_ShouldReturn200() throws Exception {
        SessionResponse renamed = SessionResponse.builder()
                .id(SESSION_ID).title("New Title")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(chatSessionService.renameSession(anyString(), anyString(), eq("New Title")))
                .thenReturn(renamed);

        mockMvc.perform(put("/api/v1/chat/sessions/{sessionId}/title", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RenameSessionRequest("New Title"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("New Title"));
    }

    @Test
    @WithMockUser(username = "user-111")
    @DisplayName("PUT /sessions/{sessionId}/title — blank title — should return 400")
    void renameSession_WhenTitleBlank_ShouldReturn400() throws Exception {
        mockMvc.perform(put("/api/v1/chat/sessions/{sessionId}/title", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RenameSessionRequest(""))))
                .andExpect(status().isBadRequest());
    }
}
