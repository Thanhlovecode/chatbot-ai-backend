# 📊 Benchmark Report — Spring AI Chatbot

> **Date**: 2026-04-14 ~ 2026-04-15  
> **Hardware**: 16 cores, 32 GB RAM, Windows + Docker  
> **Test Tool**: k6 (ramping-vus, SSE streaming)  
> **Profile**: `application-test.yaml` (Mock LLM + Mock RAG)

---

## Test Configuration

| Component | Config |
|-----------|--------|
| **Spring Boot** | 3.x + Virtual Threads (Java 21) |
| **Redis** | 7.x Docker — Lettuce pool: max-active 80, command timeout 3s |
| **PostgreSQL** | 16.x Docker — HikariCP: max-pool 20, timeout 3s |
| **CB Redis** | COUNT_BASED, window 20, failure-rate 60%, slow-call 80%/500ms, wait 30s |
| **Bulkhead DB** | max-concurrent 18 (HikariCP 20 − 2 headroom), max-wait 2s |
| **Mock LLM** | TTFB 300-2000ms, 40 tokens/response, 30ms interval, 2% failure rate |

---

## Test Results Summary

### All Runs Comparison (2000 VU)

| Metric | Run 2 (Baseline) | Run 3 (+Bulkhead) | Run 4 (+Tách k6) |
|--------|:---:|:---:|:---:|
| **Throughput** | 390 rps | 390.9 rps | ~390 rps |
| **Latency p50** | 2.37s | 2.38s | **2.09s** ↓ |
| **Latency p95** | 3.16s | 3.16s | 3.18s |
| **Latency p99** | 3.30s | 3.25s | 3.46s |
| **System CPU Peak** | **98.2%** 🔴 | **98.9%** 🔴 | **78.3%** ✅ |
| **Process CPU Peak** | 51.2% | 52.1% | **37.5%** ✅ |
| **CB State** | OPEN 🔴 | OPEN 🔴 | **CLOSED** ✅ |
| **HikariCP Active (max)** | ~18-20 | 13 | **~2** |
| **HikariCP Pending** | ⚠️ có | **0** ✅ | **0** ✅ |
| **Redis Clients** | 43 | 77 | **28** |
| **Redis Latency p99 (max)** | 230ms | 85.2ms | **106ms** |
| **Heap Used** | — | — | **9.7%** |
| **Error Rate** | 0.002% | 0.005% | ~2% (mock) |
| **Thay đổi** | — | +Bulkhead +4-Tier | +k6 tách máy |

### Run 1: Baseline 1500 VU (k6 cùng máy)

| Metric | Value |
|--------|-------|
| Throughput | 308 rps |
| Latency p50/p95/p99 | 2.39s / 3.06s / 3.21s |
| System CPU Peak | 83.5% |
| CB State | CLOSED ✅ |
| Error Rate | 0.002% |

**Kết luận**: Safe Zone — hệ thống ổn định, CPU còn headroom.

---

## Key Findings

### 1. CPU Competition — k6 chiếm ~20% CPU

> [!IMPORTANT]
> Khi k6 chạy **cùng máy** với SUT, nó tranh ~20% CPU. JVM bị OS scheduler bỏ đói (CPU Starvation),
> dẫn đến perceived Redis latency tăng ảo → CB trip false positive.
>
> **Run 4 (tách k6)** xác nhận:
> - System CPU: 98.9% → **78.3%** (giảm 20.6%)
> - Process CPU: 52.1% → **37.5%** (JVM thoải mái)
> - CB: OPEN → **CLOSED** (không còn false positive)

### 2. Bulkhead bảo vệ DB hoàn hảo

Run 2 (không Bulkhead): 223K fallback operations đổ vào DB → connection queue pressure.  
Run 3 (có Bulkhead): cap 18 concurrent → **HikariCP pending = 0, timeout = 0**.  
Throughput không bị ảnh hưởng (390 rps giữ nguyên).

### 3. 4-Tier Priority hoạt động đúng

| Tier | Operation | Khi CB OPEN | Verified |
|------|-----------|-------------|----------|
| 🔴 CRITICAL | `pushToStream` | DB fallback (Bulkhead) | ✅ 0 message loss |
| 🟡 NON-CRITICAL | `getZSetSize` | Reject 503 | ✅ |
| ⚪ FIRE-AND-FORGET | `touchSession` | Skip | ✅ 118K skips |
| 🟢 FAIL-OPEN | `checkTokenBucket` | Return null | ✅ |

### 4. CB Root Cause — Lettuce Pool Micro-Exhaustion (Run 3)

CB mở khi p99=85ms (<<500ms threshold) → trigger là **FAILURE_RATE ≥60%**, không phải slow calls.

**Chuỗi sự kiện**: CPU 98.9% → thread scheduling delay → Lettuce pool 77/80 (96%) → micro-burst đẩy pool full → `RedisConnectionFailureException` → 12/20 calls fail → CB OPEN.

> [!NOTE]
> Latency histogram chỉ track successful calls. Failed connection borrows (timeout/refused)
> không xuất hiện trong histogram nhưng VẪN được CB ghi nhận là failure.

### 5. Recovery tự động ~30 giây

Sau khi load giảm: CPU hạ → latency bình thường → CB HALF_OPEN → thăm dò OK → **CB CLOSED**.
Tổng recovery: ~30-35s (phụ thuộc `wait-duration-in-open-state`).

---

## Saturation Point

| Setup | Saturation Point | CPU tại 2000 VU | CB State |
|-------|:---:|:---:|:---:|
| k6 cùng máy | **~1,800 VU** | 98.9% 🔴 | OPEN |
| k6 tách máy | **~2,800 VU** (ước tính) | 78.3% ✅ | CLOSED |

> Tách k6 tăng capacity **+55%** nhờ giải phóng ~20% CPU bị k6 chiếm.

---

## Production Capacity Estimate

Giả định: Think Time ~60s, VU đồng thời ≈ 20% users online.

| Giai Đoạn | VU Đồng Thời | Users Online | CPU |
|-----------|:---:|:---:|---|
| ✅ An Toàn | ≤ 1,500 | ~7,500 | < 60% |
| ✅ Đề Xuất | ≤ 2,000 | ~10,000 | ~78% |
| ⚠️ Cận Bão Hòa | ~2,500 | ~12,500 | ~86% |
| 🔴 Báo Động | ≥ 2,800 | ~14,000+ | > 92% |

> [!NOTE]
> Dựa trên **Mock LLM**. Production với Real Gemini API (TTFB 2-8s) sẽ shift bottleneck
> từ CPU → Gemini API rate limit + SSE connection hold time. Cần benchmark riêng.

---

## Recommendations

| # | Action | Impact | Effort |
|---|--------|--------|--------|
| 1 | ~~Tách k6 ra máy riêng~~ | ✅ **ĐÃ LÀM** — CPU 98.9% → 78.3%, CB CLOSED | — |
| 2 | ~~Triển khai Bulkhead + 4-Tier~~ | ✅ **ĐÃ LÀM** — HikariCP pending = 0 | — |
| 3 | Tăng Lettuce `max-active: 120` | Giảm pool micro-exhaustion risk | Thấp |
| 4 | Tăng CB `sliding-window-size: 50` | Kháng burst tốt hơn | Thấp |
| 5 | Horizontal: 2 instances + LB | ~2x capacity (~5,000 VU) | Trung bình |
| 6 | JVM tuning (G1GC, carrier threads) | +5-10% throughput | Thấp |
| 7 | SSE token batching (5 tokens/event) | -15-20% CPU per request | Trung bình |
| 8 | Benchmark Real Gemini API | Xác định production bottleneck thực | Cao |
| 9 | Sustained 30-min load test | Phát hiện memory/connection leak | Trung bình |

---

## How to Reproduce

```bash
# Máy SUT (Server):
docker compose up -d redis postgres prometheus grafana
mvn spring-boot:run -Dspring.profiles.active=test

# Máy k6 (Load Generator — máy riêng):
k6 run -e BASE_URL=http://<server-ip>:8080 loadtest/stress-test-find-limit.js

# Xem kết quả:
# Grafana: http://<server-ip>:3001
# Actuator: http://<server-ip>:8080/actuator/metrics/
```

---

## Test Limitations

| # | Limitation | Impact |
|---|-----------|--------|
| 1 | **Mock LLM** (TTFB 300-2000ms vs Real 2-8s) | Production throughput & bottleneck sẽ khác |
| 2 | **Short duration** (5 phút/run) | Không detect memory leak, GC pressure dài hạn |
| 3 | **Single instance** | Chưa validate horizontal scaling |
| 4 | **No Auth/Rate Limit** (test profile) | Production có thêm JWT + rate limit overhead |
| 5 | **Windows desktop** (không phải Linux server) | Scheduling behavior có thể khác |
| 6 | **Prometheus 15s scrape** | Không capture sub-second pool spikes |
