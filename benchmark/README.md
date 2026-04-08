# 📊 Benchmark Report — Spring AI Chatbot Performance

> **Date**: 2026-04-08 ~ 2026-04-09  
> **Environment**: Single machine — 16 cores, 32 GB RAM, Windows + Docker  
> **Test Tool**: k6 (ramping-vus, SSE streaming)  
> **Profile**: `application-test.yaml` (Mock LLM + Mock RAG)

---

## 📋 Table of Contents

- [Test Configuration](#test-configuration)
- [Test Results Summary](#test-results-summary)
- [Before vs After Comparison](#before-vs-after-comparison)
- [Root Cause Analysis](#root-cause-analysis)
- [Grafana Metrics Analysis](#grafana-metrics-analysis)
- [Production Capacity Estimate](#production-capacity-estimate)
- [Recommendations](#recommendations)

---

## Test Configuration

### Infrastructure

| Component | Version | Config |
|-----------|---------|--------|
| **Spring Boot** | 3.x + Virtual Threads | JVM default heap, G1GC |
| **Redis** | 7.x (Docker) | `maxmemory-policy: allkeys-lru` |
| **PostgreSQL** | 16.x (Docker) | Production-tuned WAL, autovacuum |
| **Qdrant** | Latest (Docker) | Mocked during test |
| **Java** | 21 (Virtual Threads enabled) | Carrier threads: 100 |

### k6 Stress Test Profile

```
Phase 1:  0 → 500 VU    (30s warmup)
Phase 2:  500 VU         (1m sustain)
Phase 3:  500 → 1000 VU  (30s ramp)
Phase 4:  1000 VU        (1m sustain)
Phase 5:  1000 → 2000 VU (30s ramp)
Phase 6:  2000 VU        (2m sustain)
Phase 7:  2000 → 2500 VU (30s ramp)
Phase 8:  2500 VU        (2m sustain)
Phase 9:  2500 → 3000 VU (30s ramp)
Phase 10: 3000 VU        (2m sustain) ← peak
Phase 11: 3000 → 0 VU    (1m cooldown)
Total Duration: ~11 minutes 30 seconds
```

### Mock LLM Simulation

| Parameter | Value |
|-----------|-------|
| TTFB (Time To First Byte) | Random 300-2000ms |
| Tokens per response | 40 |
| Token interval | 30ms |
| Total stream time | ~1,200ms |
| Failure rate | 2% |

---

## Test Results Summary

### Run 1: BEFORE Fix — No Lettuce Connection Pool

| File | [`stress-2026-04-08T16-27-38-975Z.json`](../loadtest/results/stress-2026-04-08T16-27-38-975Z.json) |
|------|------|

| Metric | Value |
|--------|-------|
| **Total Requests** | 82,262 |
| **Duration** | 11m 33s (693,272ms) |
| **Throughput** | 118.6 req/s |
| **Avg Request Duration** | 15,517ms |
| **Median (p50)** | 16,043ms |
| **p90** | 27,249ms |
| **p95** | 29,722ms |
| **Max** | 46,867ms |
| **TTFB (avg)** | 14,212ms |
| **Error Rate** | 0.0036% (3/82,262) |
| **SSE Messages** | 2,997,662 (4,324/s) |
| **New Sessions** | 17,315 |
| **Data Received** | 565.7 MB (816 KB/s) |

**Issues observed:**
- `io.netty.channel.AbstractChannel$AnnotatedSocketException: Address already in use: getsockopt: localhost/127.0.0.1:6379`
- `RedisConnectionFailureException: Unable to connect to Redis`
- Redis Commands/sec dropped from 3K to near 0 during peak load

---

### Run 2: AFTER Fix — Lettuce Pool + Redis 512MB

| File | [`stress-2026-04-08T17-23-03-852Z.json`](../loadtest/results/stress-2026-04-08T17-23-03-852Z.json) |
|------|------|

| Metric | Value |
|--------|-------|
| **Total Requests** | 367,558 |
| **Duration** | 11m 37s (697,727ms) |
| **Throughput** | 526.8 req/s |
| **Avg Request Duration** | 2,844ms |
| **Median (p50)** | 2,856ms |
| **p90** | 3,622ms |
| **p95** | 3,886ms |
| **Max** | 6,963ms |
| **TTFB (avg)** | 1,468ms |
| **Error Rate** | 0.0043% (16/367,558) |
| **SSE Messages** | 14,477,857 (20,750/s) |
| **New Sessions** | 68,270 |
| **Data Received** | 2.72 GB (3.9 MB/s) |

**Issues observed:**
- None — zero BindException, zero Redis connection failures
- Redis memory reached 97% of 512MB (acceptable with `allkeys-lru`)

---

## Before vs After Comparison

### 📈 Performance Improvement

| Metric | ❌ Before | ✅ After | Improvement |
|--------|----------|---------|-------------|
| **Throughput** | 118.6 req/s | 526.8 req/s | **4.4x** 🚀 |
| **Avg Latency** | 15,517ms | 2,844ms | **5.5x faster** 🚀 |
| **Median Latency** | 16,043ms | 2,856ms | **5.6x faster** 🚀 |
| **p95 Latency** | 29,722ms | 3,886ms | **7.6x faster** 🚀 |
| **Max Latency** | 46,867ms | 6,963ms | **6.7x faster** 🚀 |
| **SSE Rate** | 4,324 msg/s | 20,750 msg/s | **4.8x** 🚀 |
| **Total Requests** | 82,262 | 367,558 | **4.5x more** |
| **Data Transfer** | 816 KB/s | 3.9 MB/s | **4.8x** |
| **BindException** | ❌ Yes | ✅ None | **Fixed** |
| **Redis Evictions** | N/A | 0 | ✅ **Perfect** |

### 📉 Latency Distribution

```
Before Fix:
  p50  ████████████████████████████████████████ 16,043ms
  p90  ████████████████████████████████████████████████████████████████████ 27,249ms
  p95  ████████████████████████████████████████████████████████████████████████████ 29,722ms

After Fix:
  p50  ███████ 2,856ms
  p90  █████████ 3,622ms
  p95  ██████████ 3,886ms
```

---

## Root Cause Analysis

### Problem: `Address already in use: getsockopt` (BindException)

**Root Cause**: `RedisConfig.java` created a custom `@Bean LettuceConnectionFactory` which **completely disabled** Spring Boot's auto-configuration. As a result, all connection pool settings in YAML (`lettuce.pool.enabled: true`, `max-active: 50`) were **silently ignored**.

Without a connection pool:
1. Every `executePipelined()` call opened a **new TCP connection** to Redis
2. Each TCP connection consumed an OS ephemeral port (range: 49152-65535)
3. With 3000 VU × 2 Redis calls/request ≈ 6,000+ connections/second
4. Closed connections enter `TIME_WAIT` state (240s on Windows)
5. **Ephemeral port exhaustion** → `BindException`

### Fix Applied

**Before** (broken):
```java
// ❌ Spring Boot auto-config DISABLED — YAML pool settings IGNORED
@Bean
public LettuceConnectionFactory redisConnectionFactory() {
    LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
        .clientOptions(clientOptions)
        .build();  // NO .poolConfig() → no connection reuse
    return new LettuceConnectionFactory(serverConfig, clientConfig);
}
```

**After** (fixed):
```java
// ✅ Spring Boot auto-config ACTIVE — YAML pool settings APPLIED
@Bean
public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
    return builder -> {
        builder.clientOptions(clientOptions)
               .commandTimeout(Duration.ofSeconds(30))
               .shutdownTimeout(Duration.ofSeconds(2));
    };
    // Pool config from YAML is automatically applied by Spring Boot
}
```

### Additional Fix: Redis Memory

| Config | Before | After |
|--------|--------|-------|
| `--maxmemory` | 128mb → 256mb | **512mb** |
| Docker `memory` limit | 256M | **768M** |
| Docker `cpus` limit | 0.25 | **0.50** |

---

## Grafana Metrics Analysis

### Spring Boot 3.x (After Fix)

| Metric | Mean | Max | Assessment |
|--------|------|-----|-----------|
| System CPU | 0.393 | 1.0 | ⚠️ Brief spike to 100% |
| Process CPU | 0.173 | 0.679 | ✅ App uses <68% of 1 core |
| Load Average | 16 | 16 | ✅ Matches core count |
| Live Threads | 89.9 | 100 | ✅ Virtual threads via carriers |
| G1 Old Gen | — | ~8 GiB | ⚠️ High but GC recovers |
| Direct Buffers | ~63 MiB | ~67 MiB | ✅ NIO buffers stable |

### Redis (After Fix)

| Metric | Before Fix | After Fix |
|--------|-----------|-----------|
| Connected Clients | 3 (no pool) | **28-57** (pool active) |
| Commands/sec | 3K peak → 0 (crash) | **14.5K peak** (stable) |
| Hits/sec | 500 peak | **6K-24K peak** |
| Memory Usage | 65% of 128MB | 97% of 512MB |
| Evicted Keys | N/A | **0** |
| Network I/O | ~256 KiB/s | **~1.5 MiB/s** |

### PostgreSQL (Stable across both runs)

| Metric | Value |
|--------|-------|
| Active Sessions | 1-2 (HikariCP managed) |
| Commits | Peak ~500/interval |
| Fetch Data (SELECT) | chatbot: 1.27M, loadtest: 616K |
| Insert Data | chatbot_loadtest: mean 413K |
| Lock Contention | Normal (no deadlocks) |

---

## Production Capacity Estimate

### Mock vs Real Gemini API Timing

| Component | Mock (test) | Real Gemini 2.5 Flash |
|-----------|------------|----------------------|
| RAG/Qdrant search | 0ms | 200-500ms |
| TTFB | 300-2000ms | 2,000-5,000ms |
| Token streaming | 40 tok × 30ms = 1.2s | 200-500 tok × 40-80ms = 8-40s |
| Redis + DB | ~200ms | ~200ms |
| **Total** | **~3s** | **~12-25s** |

### Key Insight

The server proved it can handle **527 req/s** throughput. In production with real Gemini API (~15s/response), the bottleneck shifts to Gemini's response time, NOT the server.

### Estimated Concurrent Users (Single Machine — 16 cores, 32GB)

| Load Level | Concurrent Streams | Real Users Online | Server CPU |
|------------|-------------------|------------------|-----------|
| **Comfortable** | 1,000 | **~5,000** | ~15% |
| **Recommended** | 2,000 | **~10,000** | ~25% |
| **Tested OK** | 3,000 | **~15,000** | ~36% |
| **Stress** | 5,000 | **~25,000** | ~60% |

> **Note**: "Real Users Online" assumes typical think-time of ~60s between messages. Each user is actively streaming only ~20% of their session time.

---

## Recommendations

### Implemented ✅

1. **Lettuce Connection Pool** — Switch from `@Bean LettuceConnectionFactory` to `LettuceClientConfigurationBuilderCustomizer`
2. **Redis Memory** — Increased from 128MB to 512MB with Docker container limit of 768MB
3. **Redis CPU** — Increased Docker CPU limit from 0.25 to 0.50 cores

### Future Improvements 🔮

| Priority | Action | Expected Impact |
|----------|--------|----------------|
| 🟡 HIGH | Add Resilience4j CircuitBreaker for Redis operations | Fail-fast when Redis overloaded |
| 🟡 HIGH | Increase Redis to 1GB if testing >3000 VU | Prevent memory pressure |
| 🟢 MED | OS ephemeral port tuning (Windows `TcpTimedWaitDelay`) | Extra resilience |
| 🟢 MED | Monitor `redis_avg_time_per_command` with alert >50ms | Proactive alerting |
| 🔵 LOW | Horizontal scaling: 2-3 instances + Load Balancer | 30K-45K users |
| 🔵 LOW | Full K8s + Redis Cluster + PgBouncer | 100K+ users |

---

## How to Reproduce

```bash
# 1. Start infrastructure
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# 2. Start Spring Boot with test profile
# In IDE: set SPRING_PROFILES_ACTIVE=test
# Or: mvn spring-boot:run -Dspring-boot.run.profiles=test

# 3. Run stress test
k6 run loadtest/stress-test-find-limit.js

# 4. Monitor in Grafana
# Open http://localhost:3001 (admin/okeconde)
# Check: Spring Boot 3.x Statistics, Redis Dashboard, PostgreSQL Database
```
