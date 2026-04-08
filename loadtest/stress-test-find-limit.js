/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║       k6 Stress Test — Find System Breaking Point               ║
 * ║                                                                  ║
 * ║  Mục tiêu: Ramp từ 500 → 3000 VU để tìm crash point            ║
 * ║  Mô phỏng: Mỗi VU = 1 user thật, tạo session → chat tiếp      ║
 * ║                                                                  ║
 * ║  Kiến trúc k6: Mỗi VU chạy trong JS VM riêng biệt (Go-based)  ║
 * ║  → Module-scope variables tự động isolated per-VU               ║
 * ║                                                                  ║
 * ║  Usage:                                                          ║
 * ║  k6 run loadtest/stress-test-find-limit.js                       ║
 * ║                                                                  ║
 * ║  Thời gian chạy dự kiến: ~11 phút                               ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// ── Custom Metrics ─────────────────────────────────────────────────
const streamDuration = new Trend('stream_duration_ms', true);
const streamErrors = new Rate('stream_error_rate');
const messagesReceived = new Counter('sse_messages_received');
const newSessions = new Counter('new_sessions_created');
const reusedSessions = new Counter('reused_sessions');

// ── Stress Test Stages — Ramp 500 → 3000 ──────────────────────────
export const options = {
    scenarios: {
        stress_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // Phase 1: Warm up → 500 VU (baseline OK)
                { duration: '30s', target: 500 },
                { duration: '1m',  target: 500 },

                // Phase 2: Ramp → 1000 VU
                { duration: '30s', target: 1000 },
                { duration: '1m',  target: 1000 },

                // Phase 3: Ramp → 2000 VU (đã test, biết OK)
                { duration: '30s', target: 2000 },
                { duration: '2m',  target: 2000 },

                // Phase 4: Vượt ngưỡng → 2500 VU
                { duration: '30s', target: 2500 },
                { duration: '2m',  target: 2500 },

                // Phase 5: Push to limit → 3000 VU (giới hạn an toàn trên 1 máy)
                { duration: '30s', target: 3000 },
                { duration: '2m',  target: 3000 },

                // Cooldown — giảm từ từ để quan sát recovery
                { duration: '1m', target: 0 },
            ],
        },
    },
    thresholds: {
        // Loose thresholds — mục tiêu là QUAN SÁT, không pass/fail
        http_req_duration: ['p(50)<60000'],   // Chỉ fail nếu median > 60s
        stream_error_rate: ['rate<0.50'],     // Chỉ fail nếu > 50% error
    },
};

// ── Test Data ──────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const MIN_MESSAGES_PER_SESSION = 3;
const MAX_MESSAGES_PER_SESSION = 8;

const MESSAGES = [
    'Virtual Threads có lợi ích gì trong Java 21?',
    'Spring Boot 3.5 có gì mới so với 3.4?',
    'Redis Stream hoạt động như thế nào trong microservices?',
    'Giải thích CQRS pattern và ưu nhược điểm',
    'HikariCP connection pool tuning best practices',
    'Prometheus monitoring setup cho Spring Boot',
    'Docker compose production best practices',
    'JPA batch insert optimization techniques',
    'Làm sao để thiết kế API RESTful tốt?',
    'So sánh PostgreSQL và MySQL cho high concurrency',
    'Circuit Breaker pattern hoạt động như thế nào?',
    'Cách tối ưu hoá Qdrant vector search performance',
    'Giải thích cách RAG pipeline hoạt động',
    'WebSocket vs SSE cho real-time applications',
    'Kubernetes deployment strategies cho Spring Boot',
];

const FOLLOW_UPS = [
    'Bạn có thể giải thích thêm không?',
    'Cho tôi ví dụ cụ thể về điều này',
    'So sánh với cách tiếp cận khác thì sao?',
    'Làm sao áp dụng vào dự án thực tế?',
    'Performance của cách này như thế nào?',
    'Có best practice nào cần lưu ý?',
    'Tóm tắt lại những điểm chính giúp tôi',
    'Nếu dùng trong production thì cần chú ý gì?',
    'Có thể kết hợp với Spring Security không?',
    'Trade-off của giải pháp này là gì?',
];

// ── Per-VU State ───────────────────────────────────────────────────
// k6 chạy mỗi VU trong JS VM riêng biệt (Go-based isolation).
// Module-scope variables tự động là bản sao riêng của từng VU.
let sessionId = null;
let messageCount = 0;
let maxMessages = 0;

// ── Helper Functions ───────────────────────────────────────────────

function randomSessionLength() {
    return MIN_MESSAGES_PER_SESSION +
        Math.floor(Math.random() * (MAX_MESSAGES_PER_SESSION - MIN_MESSAGES_PER_SESSION + 1));
}

function pickMessage(msgCount) {
    if (msgCount === 0) {
        return MESSAGES[Math.floor(Math.random() * MESSAGES.length)];
    }
    return FOLLOW_UPS[Math.floor(Math.random() * FOLLOW_UPS.length)];
}

function extractSessionId(responseBody) {
    if (!responseBody) return null;
    const match = responseBody.match(/"sessionId"\s*:\s*"([^"]+)"/);
    return match ? match[1] : null;
}

// ── Main Test Function ─────────────────────────────────────────────
export default function () {
    // Quyết định: tạo session mới hay tiếp tục session cũ?
    if (sessionId === null || messageCount >= maxMessages) {
        sessionId = null;
        messageCount = 0;
        maxMessages = randomSessionLength();
        newSessions.add(1);
    } else {
        reusedSessions.add(1);
    }

    const msg = pickMessage(messageCount);

    const payload = JSON.stringify({
        message: msg,
        sessionId: sessionId,
    });

    const start = Date.now();

    const res = http.post(`${BASE_URL}/api/test/chat/stream`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
            'X-VU-Id': `${__VU}`,
        },
        timeout: '60s',
    });

    const duration = Date.now() - start;
    streamDuration.add(duration);

    const success = check(res, {
        'status is 200': (r) => r.status === 200,
        'response has body': (r) => r.body && r.body.length > 0,
        'response contains data': (r) => r.body && r.body.includes('data:'),
    });

    if (!success) {
        streamErrors.add(1);
        sessionId = null;
        messageCount = 0;

        if (res.status !== 200) {
            console.warn(`[VU=${__VU}] FAIL: status=${res.status}, session=${sessionId || 'NEW'}`);
        }
    } else {
        streamErrors.add(0);

        const events = (res.body.match(/data:/g) || []).length;
        messagesReceived.add(events);

        if (sessionId === null) {
            const extracted = extractSessionId(res.body);
            if (extracted) sessionId = extracted;
        }

        messageCount++;
    }

    // Think time — câu đầu đọc lâu hơn, follow-up nhanh hơn
    if (messageCount <= 1) {
        sleep(0.5 + Math.random() * 1.0);   // 0.5 - 1.5s
    } else {
        sleep(0.3 + Math.random() * 0.7);   // 0.3 - 1.0s
    }
}

// ── Summary ────────────────────────────────────────────────────────
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [`loadtest/results/stress-${timestamp}.json`]: JSON.stringify(data, null, 2),
    };
}
