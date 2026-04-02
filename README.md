# 🤖 AI Chatbot — Backend

> **Production-grade Spring Boot backend** cho hệ thống AI Chatbot sử dụng RAG (Retrieval-Augmented Generation) với Google Gemini, vector search (Qdrant), và kiến trúc event-driven qua Redis Streams.

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue?logo=spring)](https://docs.spring.io/spring-ai/reference/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🏗️ Architecture Overview

```
┌─────────────┐     ┌──────────────────────────────────────────────────┐
│   Frontend   │────▶│              Spring Boot Backend                 │
│  (Vite + TS) │◀────│                                                  │
└─────────────┘     │  ┌──────────┐ ┌───────────┐ ┌────────────────┐  │
                    │  │   Auth   │ │   Chat    │ │  Admin (RBAC)  │  │
                    │  │ (Google  │ │ (SSE      │ │  - Dashboard   │  │
                    │  │  OAuth2) │ │  Stream)  │ │  - Crawler     │  │
                    │  └────┬─────┘ └─────┬─────┘ │  - Documents   │  │
                    │       │             │        │  - Jobs        │  │
                    │       ▼             ▼        └───────┬────────┘  │
                    │  ┌──────────────────────────────────────────┐    │
                    │  │         Service Layer                     │    │
                    │  │  RAG · LLM · Rerank · Rate Limit         │    │
                    │  │  Redis Streams · Session · Token Counter  │    │
                    │  └────┬──────────┬──────────┬──────────┬────┘    │
                    └───────┼──────────┼──────────┼──────────┼────────┘
                            ▼          ▼          ▼          ▼
                       ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐
                       │Postgres│ │ Redis  │ │ Qdrant │ │  Gemini  │
                       │  (DB)  │ │(Cache/ │ │(Vector │ │  (LLM)   │
                       │        │ │Stream) │ │ Store) │ │          │
                       └────────┘ └────────┘ └────────┘ └──────────┘
```

---

## ⚡ Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| **Runtime** | Java 21 (Virtual Threads) | High-concurrency async processing |
| **Framework** | Spring Boot 3.5 | Core framework |
| **AI** | Spring AI 1.1 + Google Gemini | LLM chat & embedding |
| **Vector DB** | Qdrant 1.13 | Semantic search & RAG retrieval |
| **Database** | PostgreSQL 16 | Persistent storage |
| **Cache/Stream** | Redis 8 | Caching, rate limiting, pub/sub streams |
| **Migration** | Flyway | Database schema versioning |
| **Resilience** | Resilience4j | Circuit breaker & rate limiter |
| **Security** | Spring Security + JWT RS256 | Authentication & authorization |
| **Auth** | Google OAuth2 | Social login |
| **Doc Parser** | Apache Tika + Spring AI PDF | Multi-format document ingestion |
| **Reranking** | Cohere Rerank | Improve RAG retrieval quality |
| **Container** | Docker + Docker Compose | Infrastructure orchestration |

---

## 📋 Prerequisites

- **Java 21** (JDK) — [Download](https://adoptium.net/)
- **Maven 3.9+** (hoặc dùng Maven Wrapper `./mvnw` đi kèm)
- **Docker & Docker Compose** — cho PostgreSQL, Redis, Qdrant
- **OpenSSL** — để generate RSA keys cho JWT
- **Google Cloud Console** — tạo OAuth2 Client ID
- **Gemini API Key** — từ [Google AI Studio](https://aistudio.google.com/)
- **Cohere API Key** — từ [Cohere Dashboard](https://dashboard.cohere.com/)

---

## 🚀 Quick Start

### 1. Clone & Copy Environment

```bash
git clone https://github.com/Thanhlovecode/chatbot-ai-backend.git
cd chatbot-ai-backend

# Copy template → fill in your real values
cp .env.example .env
```

> ⚠️ **Mở `.env` và điền đầy đủ API keys, passwords trước khi tiếp tục.**

### 🔑 2. Generate RSA Keys for JWT

Spring Boot cần cặp RSA key để ký và xác minh JWT. Chạy các lệnh sau để tự sinh key:

```bash
mkdir -p src/main/resources/keys
openssl genrsa -out src/main/resources/keys/private.pem 2048
openssl rsa -in src/main/resources/keys/private.pem -pubout -out src/main/resources/keys/public.pem
```

> 🔒 Thư mục `keys/` đã có trong `.gitignore` — key KHÔNG được commit lên Git.

### 3. Start Infrastructure (Docker Compose)

```bash
# Chỉ start PostgreSQL, Redis, Qdrant (không start spring-ai container)
docker compose up -d postgres redis qdrant
```

### 4. Run the Application

```bash
# Sử dụng Maven Wrapper (khuyến nghị)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Hoặc nếu đã install Maven globally
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Ứng dụng sẽ khởi động tại **http://localhost:8080**.

### 5. Verify

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

---

## 🔑 Environment Variables

| Variable | Description | Example |
|----------|------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `dev` / `prod` |
| `GEMINI_API_KEY` | Google Gemini API key | `AIza...` |
| `COHERE_API_KEY` | Cohere Rerank API key | `RO1M...` |
| `GOOGLE_CLIENT_ID` | Google OAuth2 Client ID | `4841...apps.googleusercontent.com` |
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `chatbot` |
| `DB_USERNAME` | Database username | `postgres` |
| `DB_PASSWORD` | Database password | `***` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | `***` |
| `QDRANT_HOST` | Qdrant host | `localhost` |
| `QDRANT_PORT` | Qdrant gRPC port | `6334` |
| `APP_COOKIE_SECURE` | Cookie Secure flag | `false` (dev) / `true` (prod) |
| `CORS_ALLOWED_ORIGINS` | Allowed CORS origins | `https://yourdomain.com` |

---

## 📁 Project Structure

```
chatbot-ai-backend/
├── src/main/java/dev/thanh/spring_ai/
│   ├── SpringAiApplication.java          # Entry point
│   ├── components/                        # Spring components
│   ├── config/                            # Configuration classes
│   │   ├── security/                      # Security config (JWT, CORS, filters)
│   │   ├── RedisConfig.java               # Redis + Stream config
│   │   ├── VectorStoreConfig.java         # Qdrant vector store
│   │   └── ...
│   ├── controller/                        # REST API controllers
│   │   ├── AuthController.java            # Google OAuth login/refresh/logout
│   │   ├── ChatController.java            # Chat streaming (SSE)
│   │   ├── RagController.java             # RAG file upload
│   │   ├── AdminDashboardController.java  # Admin stats
│   │   ├── AdminCrawlerController.java    # Web crawler management
│   │   ├── AdminDocumentController.java   # Document management
│   │   └── AdminJobController.java        # Job management
│   ├── dto/                               # Request/Response DTOs
│   ├── entity/                            # JPA entities
│   ├── enums/                             # Enum types
│   ├── event/                             # Application events
│   ├── exception/                         # Global exception handling
│   ├── repository/                        # Spring Data JPA repos
│   ├── scheduler/                         # Scheduled tasks
│   ├── service/                           # Business logic
│   │   ├── security/                      # Auth & JWT services
│   │   ├── ChatService.java               # Chat orchestration
│   │   ├── RagService.java                # RAG pipeline
│   │   ├── RerankService.java             # Cohere reranking
│   │   ├── RedisStreamService.java        # Event-driven messaging
│   │   ├── RateLimitService.java          # Token bucket rate limiting
│   │   └── ...
│   ├── tools/                             # AI function calling tools
│   └── utils/                             # Utility classes
├── src/main/resources/
│   ├── application.yaml                   # Base config
│   ├── application-dev.yaml               # Dev profile
│   ├── application-prod.yaml              # Production profile
│   ├── db/migration/                      # Flyway migration scripts
│   ├── lua/                               # Redis Lua scripts (rate limiting)
│   ├── prompts/                           # AI prompt templates (StringTemplate)
│   └── keys/                              # ⛔ Git-ignored RSA keys
├── database/
│   └── init_schema.sql                    # Reference schema
├── Dockerfile                             # Multi-stage Docker build
├── docker-compose.yml                     # Full stack (Postgres, Redis, Qdrant)
├── pom.xml                                # Maven dependencies
├── .env.example                           # Environment variable template
├── run.bat                                # Windows dev script
└── README.md
```

---

## 🐳 Docker Deployment

### Full Stack (bao gồm Spring Boot)

```bash
# Đảm bảo .env đã được điền đầy đủ
docker compose up -d --build
```

Các service sẽ khởi động:

| Service | Container | Port | Healthcheck |
|---------|-----------|------|-------------|
| Spring Boot | `spring-ai` | `8080` | `/actuator/health` |
| PostgreSQL 16 | `spring-ai-postgres` | `5432` | `pg_isready` |
| Redis 8 | `spring-ai-redis` | `6379` | `redis-cli ping` |
| Qdrant 1.13 | `qdrant` | `6333` (HTTP), `6334` (gRPC) | TCP check |

### Resource Limits

| Service | CPU | Memory |
|---------|-----|--------|
| Spring Boot | 1.0 core | 1024 MB |
| PostgreSQL | 0.75 core | 512 MB |
| Redis | 0.50 core | 640 MB |
| Qdrant | 0.50 core | 512 MB |

---

## 📖 API Endpoints

### 🔐 Authentication (`/api/v1/auth`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/auth/google` | Login with Google OAuth2 ID token | ❌ |
| `POST` | `/auth/refresh` | Rotate tokens (uses HttpOnly cookie) | 🍪 |
| `POST` | `/auth/logout` | Logout + clear cookie | 🔑 |

### 💬 Chat (`/api/v1/chat`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/chat/stream` | Send message, receive SSE stream response | 🔑 |
| `GET` | `/chat/sessions` | List user sessions (cursor pagination) | 🔑 |
| `GET` | `/chat/{sessionId}/messages` | Get messages (cursor pagination) | 🔑 |
| `DELETE` | `/chat/sessions/{sessionId}` | Delete a session | 🔑 |
| `PUT` | `/chat/sessions/{sessionId}/title` | Rename session | 🔑 |

### 📄 RAG (`/api/v1/rag`)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| `POST` | `/rag/file` | Upload document for RAG ingestion | 🔑 |

### 🛡️ Admin (`/api/v1/admin/*`) — ADMIN role required

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/admin/dashboard/stats` | Dashboard statistics |
| `GET` | `/admin/dashboard/topics` | Topic coverage analysis |
| `GET` | `/admin/dashboard/crawl-history` | Crawl history |
| `GET` | `/admin/documents` | List documents (paginated) |
| `DELETE` | `/admin/documents/{id}` | Delete document + vectors |
| `GET` | `/admin/crawler/pages` | List crawler pages |
| `POST` | `/admin/crawler/pages/{id}/approve` | Approve crawled page |
| `POST` | `/admin/crawler/pages/{id}/reject` | Reject crawled page |
| `POST` | `/admin/crawler/pages/approve-all` | Approve all pending |
| `GET` | `/admin/crawler/sources` | List crawl sources |
| `POST` | `/admin/crawler/sources` | Add crawl source |
| `PUT` | `/admin/crawler/sources/{id}` | Update crawl source |
| `POST` | `/admin/crawler/sources/{id}/crawl-now` | Trigger immediate crawl |
| `GET` | `/admin/jobs` | List scheduled jobs |
| `POST` | `/admin/jobs/{jobId}/run` | Trigger a job manually |

---

## 🔒 Security

- **Authentication**: Google OAuth2 → JWT RS256 (asymmetric key pair)
- **Access Token**: Short-lived (15–30 min), stored in frontend JS memory
- **Refresh Token**: Long-lived (7–30 days), stored as `HttpOnly` + `Secure` + `SameSite=Lax` cookie
- **Authorization**: Role-based (`USER`, `ADMIN`) với `@PreAuthorize`
- **Rate Limiting**: Token bucket + daily quota qua Redis Lua scripts
- **Resilience**: Circuit breaker (Resilience4j) cho external API calls
- **Password**: BCrypt (strength ≥ 12)
- **CORS**: Configurable origins qua environment variable

---

## 🧪 Testing

```bash
# Run all tests
./mvnw test

# Run with Testcontainers (requires Docker)
./mvnw verify
```

Frameworks sử dụng:
- **JUnit 5** + **Mockito** — Unit tests
- **Testcontainers** — PostgreSQL, Redis integration tests
- **WireMock** — Mock external HTTP APIs (Gemini, Cohere)
- **Spring Security Test** — `@WithMockUser` for auth tests
- **Reactor Test** — `StepVerifier` for reactive stream tests

---

## 🔗 Related Repositories

| Repository | Description |
|-----------|-------------|
| [chatbot-ai-frontend](https://github.com/Thanhlovecode/chatbot-ai-frontend) | Vite + TypeScript frontend |

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
