package dev.thanh.spring_ai.test;

import dev.thanh.spring_ai.dto.request.ChatMessageRequest;
import dev.thanh.spring_ai.dto.response.ChatResponse;
import dev.thanh.spring_ai.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Load Testing Controller — bypass JWT authentication.
 * <p>
 * ⚠️ CHỈ TỒN TẠI KHI profile=test.
 * Trên production (profile=prod), bean này KHÔNG tồn tại → endpoint 404.
 * <p>
 * Dùng 100 UUID user giả lập (deterministic) để:
 * - Tạo nhiều session giống production
 * - Phân tán load trên Redis/DB
 * - Đảm bảo mỗi VU luôn dùng CÙNG MỘT userId xuyên suốt session
 */
@RestController
@RequestMapping("/api/test")
@Profile("test")
@Slf4j(topic = "LOAD-TEST")
@RequiredArgsConstructor
public class LoadTestController {

    private final ChatService chatService;

    /**
     * 100 UUID giả lập — tạo 1 lần, dùng mãi, deterministic.
     * UUID v5 (SHA-1 based) với namespace cố định để mỗi lần restart đều giống nhau.
     */
    private static final String[] TEST_USER_IDS;

    static {
        TEST_USER_IDS = new String[100];
        for (int i = 0; i < 100; i++) {
            TEST_USER_IDS[i] = UUID.nameUUIDFromBytes(
                    ("test-user-" + i).getBytes()).toString();
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> loadTestStream(
            @Valid @RequestBody ChatMessageRequest request,
            HttpServletRequest httpRequest) {
        // k6 gửi header X-VU-Id (= __VU) để định danh VU
        // → Dùng modulo 100 để map VU → userId cố định
        // → Đảm bảo 1 VU luôn dùng cùng userId → không bị ACCESS DENIED khi reuse session
        String vuHeader = httpRequest.getHeader("X-VU-Id");
        int vuIndex;
        if (vuHeader != null) {
            try {
                vuIndex = Math.abs(Integer.parseInt(vuHeader)) % 100;
            } catch (NumberFormatException e) {
                vuIndex = Math.abs(vuHeader.hashCode()) % 100;
            }
        } else {
            vuIndex = (int) (Thread.currentThread().threadId() % 100);
        }

        String testUserId = TEST_USER_IDS[vuIndex];
        return chatService.chatStream(request, testUserId);
    }
}
