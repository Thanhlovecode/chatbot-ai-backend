# Kế hoạch Triển khai: RAG Admin Dashboard

> **Mục tiêu**: Xây dựng trang Admin Dashboard cho hệ thống RAG Chatbot bao gồm 3 tab chính:
> **Tổng quan** · **Knowledge Base** · **Background Jobs**
> _(Bỏ qua Chat Analytics theo yêu cầu)_

---

## 📐 Kiến trúc Tổng thể

```
d:\chatbot-ai\frontend\   ← Frontend (Vanilla TypeScript + Vite)
d:\spring-ai\             ← Backend  (Spring Boot 3.5 + Java 21)
```

---

## 🔗 PHẦN CHUNG (Shared Contract)

### API Base URL
```
http://localhost:8080/api/v1/admin
```
_Tất cả endpoint admin đều yêu cầu JWT Bearer Token + Role `ADMIN`_

### Auth Guard
- Header: `Authorization: Bearer <token>`
- Backend kiểm tra `UserRole.ADMIN` qua `@PreAuthorize("hasRole('ADMIN')")`
- Frontend redirect về `/` nếu không đủ quyền

### Shared Data Models (TypeScript ↔ Java)

| TypeScript Interface | Java DTO | Mô tả |
|---|---|---|
| `DashboardStats` | `DashboardStatsResponse` | Số liệu tổng quan |
| `TopicCoverage` | `TopicCoverageDto` | Phủ sóng theo chủ đề |
| `CrawlHistory` | `CrawlHistoryDto` | Lịch sử crawl 8 ngày |
| `Document` | `DocumentDto` | Tài liệu upload |
| `CrawlerPage` | `CrawlerPageDto` | Trang crawled chờ duyệt |
| `CrawlSource` | `CrawlSourceDto` | Nguồn crawl |
| `BackgroundJob` | `BackgroundJobDto` | Thông tin job |
| `JobStats` | `JobStatsDto` | Thống kê jobs |

### Pagination Pattern (tái dùng hiện có)
```typescript
// Tái dùng CursorResponse<T> đã tồn tại trong hệ thống
interface CursorResponse<T> { data: T[]; nextCursor?: string; hasMore: boolean; }
```

---

## 🖥️ PHẦN FRONTEND

### Vị trí: `d:\chatbot-ai\frontend\`

### Cấu trúc File Mới

```
frontend/
├── admin.html                    [NEW] trang dashboard admin riêng biệt
└── src/
    ├── admin/
    │   ├── main.ts               [NEW] entry point admin
    │   ├── router.ts             [NEW] tab routing (hash-based)
    │   ├── api/
    │   │   ├── dashboard.ts      [NEW] API calls Tổng quan
    │   │   ├── knowledge.ts      [NEW] API calls Knowledge Base
    │   │   └── jobs.ts           [NEW] API calls Background Jobs
    │   ├── pages/
    │   │   ├── overview/
    │   │   │   ├── OverviewPage.ts      [NEW] tab Tổng quan
    │   │   │   ├── StatsCards.ts        [NEW] 4 card số liệu
    │   │   │   ├── TopicCoverage.ts     [NEW] progress bars chủ đề
    │   │   │   └── CrawlHistoryChart.ts [NEW] bar chart 8 ngày (SVG)
    │   │   ├── knowledge/
    │   │   │   ├── KnowledgePage.ts     [NEW] tab Knowledge Base
    │   │   │   ├── UploadTab.ts         [NEW] upload + danh sách docs
    │   │   │   ├── ReviewTab.ts         [NEW] duyệt crawler pages
    │   │   │   └── SourcesTab.ts        [NEW] quản lý nguồn crawl
    │   │   └── jobs/
    │   │       ├── JobsPage.ts          [NEW] tab Background Jobs
    │   │       ├── JobStatsCards.ts     [NEW] 4 card jobs stats
    │   │       └── JobsTable.ts         [NEW] bảng danh sách jobs
    │   └── components/
    │       ├── AdminLayout.ts    [NEW] layout header + nav tabs
    │       ├── StatusBadge.ts    [NEW] badge trạng thái tái dùng
    │       ├── ProgressBar.ts    [NEW] thanh progress chủ đề
    │       └── BarChart.ts       [NEW] mini bar chart (SVG thuần)
    └── style-admin.css           [NEW] CSS riêng cho admin UI
```

> **Lưu ý**: Dùng `admin.html` **riêng biệt** thay vì tích hợp vào `index.html` để tránh ảnh hưởng chatbot UI hiện tại.

---

### Chi tiết từng Tab

#### TAB 1: Tổng quan (`admin.html#overview`)

**Các thành phần UI:**
1. **Banner thông báo** — "Có việc cần làm ngay — X trang crawler chờ duyệt" + nút "Duyệt ngay"
2. **4 Stats Cards**:
   - Tài liệu Active (số docs + vector count trong Qdrant)
   - Chờ duyệt (số pages pending từ crawler)
   - Crawl gần nhất (số trang + timestamp)
   - Queries hôm nay (tổng + % low-relevance)
3. **Phủ sóng tài liệu theo chủ đề** — thanh progress với cảnh báo ⚠️ nếu < 40%
4. **Lịch sử crawl 8 ngày** — bar chart SVG (xanh = thành công, đỏ = thất bại)

**API calls:**
```typescript
GET /api/v1/admin/dashboard/stats          → DashboardStats
GET /api/v1/admin/dashboard/topics         → TopicCoverage[]
GET /api/v1/admin/dashboard/crawl-history  → CrawlHistory[]
```

---

#### TAB 2: Knowledge Base (`admin.html#knowledge`)

**3 sub-tab:**

**2a. Upload tài liệu**
- Drop zone kéo thả (PDF, DOCX, TXT, MD, HTML · max 50MB)
- Bảng: Tên file | Kích thước | Chunks | Chủ đề | Upload lúc | Trạng thái
- Filter: Tất cả / Active / Đang xử lý / Lỗi
- Tái dùng endpoint `POST /api/v1/rag/file` hiện có

**2b. Duyệt crawler (badge số pending)**
- Banner cảnh báo + nút "Duyệt tất cả"
- Filter tabs: Tất cả / Chờ duyệt / Đã duyệt / Đã từ chối
- Bảng: Trang | Nguồn | Từ | Chunks | Crawled lúc | Hành động (Duyệt/Từ quyết)

**2c. Nguồn crawl**
- Danh sách: Tên | URL | Badge trạng thái | Lịch chạy | Lần cuối | Pages | Độ sâu
- Nút "Cấu hình" + "Crawl ngay"
- Form thêm nguồn mới ở cuối trang

**API calls:**
```typescript
// Documents
GET    /api/v1/admin/documents             → Document[]
DELETE /api/v1/admin/documents/:id

// Crawler Pages
GET    /api/v1/admin/crawler/pages?status= → CrawlerPage[]
POST   /api/v1/admin/crawler/pages/:id/approve
POST   /api/v1/admin/crawler/pages/:id/reject
POST   /api/v1/admin/crawler/pages/approve-all

// Crawl Sources
GET    /api/v1/admin/crawler/sources       → CrawlSource[]
POST   /api/v1/admin/crawler/sources
PUT    /api/v1/admin/crawler/sources/:id
POST   /api/v1/admin/crawler/sources/:id/crawl-now
```

---

#### TAB 3: Background Jobs (`admin.html#jobs`)

**Các thành phần:**
1. **4 Stats Cards**: Tổng Jobs | Đang chạy | Thành công hôm nay | Thất bại
2. **Bảng Jobs**:
   - Tên Job | Loại (badge tím/xám) | Cron/Lịch | Lần cuối chạy | Thời gian | Trạng thái | Hành động
   - "Chạy ngay" (khi idle) / "Đang chạy..." disabled (khi running)
   - Polling mỗi 10s để cập nhật trạng thái

**API calls:**
```typescript
GET  /api/v1/admin/jobs         → { stats: JobStats, jobs: BackgroundJob[] }
POST /api/v1/admin/jobs/:id/run → trigger job ngay lập tức
```

---

### Design System

- **Font**: Inter (đã có sẵn)
- **Màu chính**: `#5B4FE8` (tím — đúng màu screenshot)
- **Màu phụ**: `#22C55E` (xanh thành công), `#EF4444` (đỏ lỗi), `#F59E0B` (vàng cảnh báo)
- **Nền trang**: `#FAFAF8` (light cream — giống screenshot)
- **Card**: border-radius `12px` + border `1px solid #E5E7EB` + box-shadow nhẹ
- **Badge pending**: border `1px solid #F59E0B`, background `#FFFBEB`
- **Chart**: SVG thuần — **không dùng thư viện chart ngoài**

---

## ⚙️ PHẦN BACKEND

### Vị trí: `d:\spring-ai\`

### Cấu trúc Package Mới

```
src/main/java/dev/thanh/spring_ai/
├── controller/
│   ├── AdminDashboardController.java   [NEW]
│   ├── AdminDocumentController.java    [NEW]
│   ├── AdminCrawlerController.java     [NEW]
│   └── AdminJobController.java         [NEW]
├── service/
│   ├── AdminDashboardService.java      [NEW]
│   ├── AdminDocumentService.java       [NEW]
│   ├── CrawlerService.java             [NEW] crawl HTML bằng Jsoup
│   ├── CrawlSchedulerService.java      [NEW] trigger + log
│   └── JobRegistryService.java         [NEW] registry tất cả jobs
├── entity/
│   ├── Document.java                   [NEW]
│   ├── CrawlerPage.java                [NEW]
│   ├── CrawlSource.java                [NEW]
│   └── JobExecution.java               [NEW]
├── repository/
│   ├── DocumentRepository.java         [NEW]
│   ├── CrawlerPageRepository.java      [NEW]
│   ├── CrawlSourceRepository.java      [NEW]
│   └── JobExecutionRepository.java     [NEW]
├── dto/
│   ├── response/admin/
│   │   ├── DashboardStatsResponse.java [NEW]
│   │   ├── TopicCoverageDto.java       [NEW]
│   │   ├── CrawlHistoryDto.java        [NEW]
│   │   ├── DocumentDto.java            [NEW]
│   │   ├── CrawlerPageDto.java         [NEW]
│   │   ├── CrawlSourceDto.java         [NEW]
│   │   ├── BackgroundJobDto.java       [NEW]
│   │   └── JobStatsDto.java            [NEW]
│   └── request/admin/
│       ├── AddCrawlSourceRequest.java  [NEW]
│       └── UpdateCrawlSourceRequest.java [NEW]
├── enums/
│   ├── DocumentStatus.java    [NEW] ACTIVE, PROCESSING, ERROR
│   ├── CrawlerPageStatus.java [NEW] PENDING, APPROVED, REJECTED
│   ├── JobType.java           [NEW] SCHEDULED, MANUAL
│   └── JobStatus.java         [NEW] RUNNING, SUCCESS, FAILED, IDLE
└── scheduler/
    └── CrawlJobScheduler.java  [NEW] @Scheduled daily crawl
```

---

### DB Migration Mới

#### `V4__create_admin_tables.sql` (Flyway)

```sql
-- Tài liệu đã upload
CREATE TABLE IF NOT EXISTS documents (
    id           UUID         NOT NULL,
    file_name    VARCHAR(500) NOT NULL,
    file_size    BIGINT,
    chunk_count  INTEGER      DEFAULT 0,
    topic        VARCHAR(100),
    status       VARCHAR(30)  NOT NULL DEFAULT 'PROCESSING',
    uploaded_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    uploaded_at  TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_documents PRIMARY KEY (id)
);

-- Nguồn crawl
CREATE TABLE IF NOT EXISTS crawl_sources (
    id            UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    base_url      VARCHAR(512) NOT NULL,
    cron_schedule VARCHAR(100) DEFAULT '0 6 * * *',
    max_depth     INTEGER      DEFAULT 3,
    status        VARCHAR(30)  NOT NULL DEFAULT 'APPROVED',
    last_crawl_at TIMESTAMP,
    pages_crawled INTEGER      DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT pk_crawl_sources PRIMARY KEY (id),
    CONSTRAINT uq_crawl_sources_url UNIQUE (base_url)
);

-- Trang crawled chờ duyệt
CREATE TABLE IF NOT EXISTS crawler_pages (
    id          UUID         NOT NULL,
    source_id   UUID         REFERENCES crawl_sources(id) ON DELETE CASCADE,
    title       VARCHAR(500),
    url         VARCHAR(512) NOT NULL,
    topic_tag   VARCHAR(100),
    word_count  INTEGER      DEFAULT 0,
    chunk_count INTEGER      DEFAULT 0,
    status      VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    crawled_at  TIMESTAMP    NOT NULL,
    reviewed_at TIMESTAMP,
    reviewed_by UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT pk_crawler_pages PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_crawler_pages_status ON crawler_pages(status);

-- Lịch sử chạy job
CREATE TABLE IF NOT EXISTS job_executions (
    id           UUID         NOT NULL,
    job_id       VARCHAR(100) NOT NULL,
    job_name     VARCHAR(255) NOT NULL,
    job_type     VARCHAR(30)  NOT NULL DEFAULT 'SCHEDULED',
    status       VARCHAR(30)  NOT NULL,
    duration_ms  BIGINT,
    pages_count  INTEGER,
    error_msg    TEXT,
    started_at   TIMESTAMP    NOT NULL,
    finished_at  TIMESTAMP,
    CONSTRAINT pk_job_executions PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_job_executions_job_id ON job_executions(job_id);
CREATE INDEX IF NOT EXISTS idx_job_executions_started ON job_executions(started_at DESC);
```

---

### API Endpoints Chi tiết

#### `AdminDashboardController` — `/api/v1/admin/dashboard`

```java
@GetMapping("/stats")
ResponseEntity<ResponseData<DashboardStatsResponse>> getStats()
// Query: documents active count, Qdrant vector count, pending pages count
// last job_execution (SUCCESS), today queries count from Redis/DB

@GetMapping("/topics")
ResponseEntity<ResponseData<List<TopicCoverageDto>>> getTopicCoverage()
// Group documents by topic → count chunks → tính % dựa trên target mỗi chủ đề

@GetMapping("/crawl-history")
ResponseEntity<ResponseData<List<CrawlHistoryDto>>> getCrawlHistory()
// Query job_executions 8 ngày → group by date → count success/fail
```

#### `AdminDocumentController` — `/api/v1/admin/documents`

```java
@GetMapping
ResponseEntity<ResponseData<Page<DocumentDto>>> listDocuments(
    @RequestParam(defaultValue = "ALL") String status,
    Pageable pageable
)

@DeleteMapping("/{id}")
ResponseEntity<Void> deleteDocument(@PathVariable UUID id)
// Xóa record DB + xóa vectors trong Qdrant theo filter metadata.documentId
```

#### `AdminCrawlerController` — `/api/v1/admin/crawler`

```java
// Pages
@GetMapping("/pages")
ResponseEntity<ResponseData<Page<CrawlerPageDto>>> listPages(
    @RequestParam(defaultValue = "ALL") String status, Pageable pageable)

@PostMapping("/pages/{id}/approve")
ResponseEntity<Void> approvePage(@PathVariable UUID id)
// → status = APPROVED → async trigger ingestion vào Qdrant

@PostMapping("/pages/{id}/reject")
ResponseEntity<Void> rejectPage(@PathVariable UUID id)

@PostMapping("/pages/approve-all")
ResponseEntity<Void> approveAllPending()

// Sources
@GetMapping("/sources")
ResponseEntity<ResponseData<List<CrawlSourceDto>>> listSources()

@PostMapping("/sources")
ResponseEntity<ResponseData<CrawlSourceDto>> addSource(
    @RequestBody @Valid AddCrawlSourceRequest req)

@PutMapping("/sources/{id}")
ResponseEntity<ResponseData<CrawlSourceDto>> updateSource(
    @PathVariable UUID id, @RequestBody @Valid UpdateCrawlSourceRequest req)

@PostMapping("/sources/{id}/crawl-now")
ResponseEntity<Void> triggerCrawl(@PathVariable UUID id)
// → @Async crawl + ghi job_executions
```

#### `AdminJobController` — `/api/v1/admin/jobs`

```java
@GetMapping
ResponseEntity<ResponseData<JobListResponse>> listJobs()
// JobListResponse = { stats: JobStatsDto, jobs: List<BackgroundJobDto> }

@PostMapping("/{jobId}/run")
ResponseEntity<Void> triggerJob(@PathVariable String jobId)
// Gọi JobRegistryService.trigger(jobId) → chạy async
```

---

### Security — Cập nhật `SecurityConfig.java`

```java
// Thêm rule cho admin endpoints:
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

---

### Dependency Mới Cần Thêm vào `pom.xml`

```xml
<!-- Jsoup: HTML web crawler / parser -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.3</version>
</dependency>
```

---

## 🗓️ Lộ trình Triển khai (4 Giai đoạn)

### Giai đoạn 1 — Nền tảng
1. ✅ DB Migration `V4__create_admin_tables.sql`
2. ✅ Entities + Repositories (Document, CrawlerPage, CrawlSource, JobExecution)
3. ✅ Cập nhật `SecurityConfig` + `UserRole` → thêm ADMIN check
4. ✅ `admin.html` + `AdminLayout.ts` + `style-admin.css` + tab routing
5. ✅ `AdminDashboardController` trả về mock/stub data đầu tiên

### Giai đoạn 2 — Tab Tổng quan
1. ✅ Backend: implement `getStats()`, `getTopicCoverage()`, `getCrawlHistory()` thật
2. ✅ Frontend: `StatsCards.ts`, `TopicCoverage.ts`, `CrawlHistoryChart.ts` (SVG bar chart)
3. ✅ Kết nối API → kiểm tra số liệu đúng

### Giai đoạn 3 — Tab Knowledge Base
1. ✅ Backend: `AdminDocumentController`, `AdminCrawlerController`, `CrawlerService` (Jsoup)
2. ✅ Frontend: `UploadTab.ts` (tái dùng `/api/v1/rag/file`), `ReviewTab.ts`, `SourcesTab.ts`
3. ✅ `approvePage()` → async trigger ingestion vào Qdrant

### Giai đoạn 4 — Tab Background Jobs
1. ✅ Backend: `JobRegistryService`, `AdminJobController`, `CrawlJobScheduler`
2. ✅ Frontend: `JobStatsCards.ts`, `JobsTable.ts` + polling 10s
3. ✅ Trigger job thủ công → cập nhật trạng thái realtime

---

## ✅ Tiêu chí Hoàn thành

- [ ] Route `/admin` chỉ accessible nếu role = ADMIN
- [ ] Tab Tổng quan hiển thị số liệu thật từ PostgreSQL + Qdrant
- [ ] Upload document → xuất hiện trong danh sách → trạng thái "Đang xử lý" → "Active"
- [ ] Duyệt crawler page → ingestion bất đồng bộ vào Qdrant
- [ ] Trigger job thủ công → trạng thái cập nhật sau polling
- [ ] Badge "Chờ duyệt" hiển thị số chính xác, cập nhật sau khi duyệt

---

## ❓ Câu hỏi Mở (Cần Xác nhận Trước Khi Thực thi)

1. **Tài khoản ADMIN**: Cách set `role = ADMIN` cho user? Thêm endpoint admin hay thủ công qua SQL UPDATE?
2. **Web Crawler thực tế**: Cần implement crawl HTML thật bằng Jsoup ngay, hay trang "Nguồn crawl" chỉ cần UI quản lý cấu hình — crawl thật để giai đoạn sau?
3. **Qdrant metadata**: Các vector trong Qdrant có field `topic` trong payload không? Để query `getTopicCoverage()` cần biết schema metadata hiện tại.
4. **Queries hôm nay**: Số "Queries hôm nay" lấy từ đâu? Đếm `chat_messages` hôm nay, hay có Redis counter riêng?
