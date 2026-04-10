package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.request.ChatMessageRequest;
import dev.thanh.spring_ai.dto.request.RenameSessionRequest;
import dev.thanh.spring_ai.dto.response.ChatResponse;
import dev.thanh.spring_ai.dto.response.ChatMessageResponse;
import dev.thanh.spring_ai.dto.response.CursorResponse;
import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.dto.response.SessionResponse;
import dev.thanh.spring_ai.service.ChatSessionService;
import dev.thanh.spring_ai.service.ChatService;
import dev.thanh.spring_ai.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;

@RestController
@RequestMapping(value = "/api/v1/chat")
@Validated
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> chatStream(@Valid @RequestBody ChatMessageRequest request) {
        String userId = SecurityUtils.getCurrentUserId().toString();
        return chatService.chatStream(request, userId);
    }

    @GetMapping("/sessions")
    public CursorResponse<SessionResponse> getSessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        String userId = SecurityUtils.getCurrentUserId().toString();
        return chatSessionService.getUserSessionsCursor(userId, cursor, limit);
    }

    @GetMapping("/{sessionId}/messages")
    public CursorResponse<ChatMessageResponse> getMessages(
            @PathVariable String sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        String userId = SecurityUtils.getCurrentUserId().toString();
        return chatSessionService.getMessagesCursor(sessionId, userId, cursor, limit);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseData<Void> deleteSession(@PathVariable String sessionId) {
        String userId = SecurityUtils.getCurrentUserId().toString();
        chatSessionService.deleteSession(sessionId, userId);
        return ResponseData.<Void>builder()
                .status(200)
                .message("Session deleted successfully")
                .timestamp(ZonedDateTime.now())
                .build();
    }

    @PutMapping("/sessions/{sessionId}/title")
    public ResponseData<SessionResponse> renameSession(
            @PathVariable String sessionId,
            @RequestBody @Valid RenameSessionRequest request) {
        String userId = SecurityUtils.getCurrentUserId().toString();
        SessionResponse response = chatSessionService.renameSession(sessionId, userId, request.title());
        return ResponseData.<SessionResponse>builder()
                .status(200)
                .message("Session renamed successfully")
                .timestamp(ZonedDateTime.now())
                .data(response)
                .build();
    }
}
