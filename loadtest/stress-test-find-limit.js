/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║       k6 Stress Test + Chaos Engineering Ready                   ║
 * ║                                                                  ║
 * ║  Mục tiêu: Ramp từ 500 → 3000 VU, đo đầy đủ metrics,           ║
 * ║  hỗ trợ manual chaos injection (kill Redis bất kỳ lúc nào).    ║
 * ║                                                                  ║
 * ║  Mô phỏng: Mỗi VU = 1 user thật, tạo session → chat tiếp      ║
 * ║                                                                  ║
 * ║  Chaos: Trong khi k6 chạy, bạn tự kill Redis container:        ║
 * ║    docker kill redis                                             ║
 * ║  Rồi restart:                                                    ║
 * ║    docker start redis                                            ║
 * ║  Observe recovery trên Grafana.                                  ║
 * ║                                                                  ║
 * ║  Usage:                                                          ║
 * ║  k6 run loadtest/stress-test-find-limit.js                       ║
 * ║                                                                  ║
 * ║  Thời gian chạy dự kiến: ~15 phút                               ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate, Gauge } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// ── Custom Metrics ─────────────────────────────────────────────────
// Stream metrics
const streamDuration = new Trend('stream_duration_ms', true);
const streamErrors = new Rate('stream_error_rate');
const messagesReceived = new Counter('sse_messages_received');

// Session metrics
const newSessions = new Counter('new_sessions_created');
const reusedSessions = new Counter('reused_sessions');

// Chaos-aware metrics
const redisFallbackDetected = new Counter('redis_fallback_detected');
const circuitBreakerOpen = new Counter('circuit_breaker_open_detected');
const directDbFallback = new Counter('direct_db_fallback_detected');
const healthCheckErrors = new Counter('health_check_errors');

// Actuator metrics (polled periodically)
const cbRedisState = new Gauge('cb_redis_state');
const redisFallbackCount = new Gauge('redis_fallback_total_gauge');
const redisSkipCount = new Gauge('redis_skip_total_gauge');
const activeStreamsGauge = new Gauge('active_streams_gauge');
const hikariActiveGauge = new Gauge('hikari_active_connections');

// ── Stress Test Stages — Long sustained phases for manual chaos ────
export const options = {
    scenarios: {
        // Main scenario — chat stream stress
        stress_test: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // Phase 1: Ramp → 500 VU
                { duration: '30s', target: 500 },
                { duration: '30s', target: 500 },

                // Phase 2: Ramp → 1000 VU
                { duration: '30s', target: 1000 },
                { duration: '30s', target: 1000 },

                // Phase 3: Ramp → 1500 VU
                { duration: '30s', target: 1500 },
                { duration: '1m', target: 1500 },

                // Phase 4: Ramp → 2000 VU
                { duration: '30s', target: 2000 },
                { duration: '1m', target: 2000 },

                // Phase 5: Ramp → 2500 VU
                { duration: '30s', target: 2500 },
                { duration: '1m', target: 2500 },

                // Phase 6: Cooldown
                { duration: '30s', target: 0 },
            ],
        },
        // Health monitor — poll actuator liên tục
        health_monitor: {
            executor: 'constant-vus',
            vus: 1,
            duration: '7m',
            exec: 'healthCheck',
        },
    },
    thresholds: {
        // Loose thresholds — mục tiêu là QUAN SÁT, không pass/fail cứng
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

// ── Main Test Function — Chat Stream ──────────────────────────────
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
        timeout: '90s',  // Tăng timeout vì khi Redis die, response có thể chậm hơn
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

        // ── Detect fallback patterns in response body ──
        if (res.body.includes('Direct DB fallback') || res.body.includes('Redis unavailable')) {
            directDbFallback.add(1);
            redisFallbackDetected.add(1);
        }

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

// ── Health Monitor — Poll Actuator Endpoints ──────────────────────
export function healthCheck() {
    try {
        // 1. Circuit Breaker state cho Redis
        // ⚠️ PHẢI filter tag name=redis VÀ state=open
        //    Không filter → SUM tất cả CB instances → false positive!
        const cbRes = http.get(
            `${BASE_URL}/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:redis&tag=state:open`, {
            tags: { name: 'health_monitor' },
            timeout: '5s',
        });
        if (cbRes.status === 200) {
            try {
                const cbData = JSON.parse(cbRes.body);
                const measurements = cbData.measurements || [];
                for (const m of measurements) {
                    if (m.statistic === 'VALUE') {
                        cbRedisState.add(m.value);
                        if (m.value >= 1) { // 1 = Redis CB đang OPEN
                            circuitBreakerOpen.add(1);
                            console.warn(`[HEALTH] Redis CB is OPEN! value=${m.value}`);
                        }
                    }
                }
            } catch (e) { /* parse error — skip */ }
        }

        // 2. Active streams
        const streamsRes = http.get(`${BASE_URL}/actuator/metrics/chat_streams_active`, {
            tags: { name: 'health_monitor' },
            timeout: '5s',
        });
        if (streamsRes.status === 200) {
            try {
                const data = JSON.parse(streamsRes.body);
                const measurements = data.measurements || [];
                for (const m of measurements) {
                    if (m.statistic === 'VALUE') {
                        activeStreamsGauge.add(m.value);
                    }
                }
            } catch (e) { /* skip */ }
        }

        // 3. HikariCP active connections
        const hikariRes = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.active`, {
            tags: { name: 'health_monitor' },
            timeout: '5s',
        });
        if (hikariRes.status === 200) {
            try {
                const data = JSON.parse(hikariRes.body);
                const measurements = data.measurements || [];
                for (const m of measurements) {
                    if (m.statistic === 'VALUE') {
                        hikariActiveGauge.add(m.value);
                    }
                }
            } catch (e) { /* skip */ }
        }

    } catch (e) {
        healthCheckErrors.add(1);
    }

    sleep(3);  // Poll mỗi 3 giây
}

// ── Setup — Capture baseline ──────────────────────────────────────
export function setup() {
    console.log('══════════════════════════════════════════════════════');
    console.log('  CHAOS ENGINEERING LOAD TEST — Starting');
    console.log('  Target: 2500 VU | Duration: 7 min');
    console.log('');
    console.log('  ⚡ Manual chaos commands:');
    console.log('    Kill Redis:    docker kill redis');
    console.log('    Restart Redis: docker start redis');
    console.log('');
    console.log('  📊 Grafana: http://localhost:3001');
    console.log('══════════════════════════════════════════════════════');

    // Capture baseline from actuator
    const baselineRes = http.get(`${BASE_URL}/actuator/metrics/chat_requests_total`, {
        timeout: '5s',
    });
    let baseline = 0;
    if (baselineRes.status === 200) {
        try {
            const data = JSON.parse(baselineRes.body);
            const measurements = data.measurements || [];
            for (const m of measurements) {
                if (m.statistic === 'COUNT') baseline = m.value;
            }
        } catch (e) { /* skip */ }
    }

    const startTime = new Date().toISOString();
    console.log(`Baseline: chat_requests_total=${baseline}, startTime=${startTime}`);

    return { baseline, startTime };
}

// ── Teardown — Verify results ─────────────────────────────────────
export function teardown(data) {
    console.log('');
    console.log('══════════════════════════════════════════════════════');
    console.log('  POST-FLIGHT VERIFICATION');
    console.log('══════════════════════════════════════════════════════');

    // Capture final metrics from actuator
    const finalRes = http.get(`${BASE_URL}/actuator/metrics/chat_requests_total`, {
        timeout: '5s',
    });
    let finalRequests = 0;
    if (finalRes.status === 200) {
        try {
            const d = JSON.parse(finalRes.body);
            const measurements = d.measurements || [];
            for (const m of measurements) {
                if (m.statistic === 'COUNT') finalRequests = m.value;
            }
        } catch (e) { /* skip */ }
    }

    // Redis fallback counter
    const fallbackRes = http.get(`${BASE_URL}/actuator/metrics/redis.fallback`, {
        timeout: '5s',
    });
    let totalFallbacks = 0;
    if (fallbackRes.status === 200) {
        try {
            const d = JSON.parse(fallbackRes.body);
            const measurements = d.measurements || [];
            for (const m of measurements) {
                if (m.statistic === 'COUNT') totalFallbacks += m.value;
            }
        } catch (e) { /* skip */ }
    }

    // Redis skip counter
    const skipRes = http.get(`${BASE_URL}/actuator/metrics/redis.skip`, {
        timeout: '5s',
    });
    let totalSkips = 0;
    if (skipRes.status === 200) {
        try {
            const d = JSON.parse(skipRes.body);
            const measurements = d.measurements || [];
            for (const m of measurements) {
                if (m.statistic === 'COUNT') totalSkips += m.value;
            }
        } catch (e) { /* skip */ }
    }

    const totalRequestsInTest = finalRequests - (data.baseline || 0);

    console.log(`  Start time:         ${data.startTime}`);
    console.log(`  Baseline requests:  ${data.baseline}`);
    console.log(`  Final requests:     ${finalRequests}`);
    console.log(`  Requests in test:   ${totalRequestsInTest}`);
    console.log(`  Redis fallbacks:    ${totalFallbacks}`);
    console.log(`  Redis skips:        ${totalSkips}`);
    console.log('');
    console.log('  🔍 Verify zero message loss with SQL:');
    console.log(`  SELECT COUNT(*) FROM chat_messages`);
    console.log(`    WHERE created_at > '${data.startTime}';`);
    console.log(`  Expected: ~${totalRequestsInTest * 2} messages`);
    console.log(`  (${totalRequestsInTest} user + ${totalRequestsInTest} assistant)`);
    console.log('══════════════════════════════════════════════════════');
}

// ── Summary ────────────────────────────────────────────────────────
export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        [`loadtest/results/chaos-${timestamp}.json`]: JSON.stringify(data, null, 2),
    };
}
