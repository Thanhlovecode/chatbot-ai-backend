-- ============================================================
-- V4: Admin Dashboard Tables
-- documents, crawl_sources, crawler_pages, job_executions
-- ============================================================

-- ─── 1. Documents (file uploads tracked in PostgreSQL) ───────────────────────
CREATE TABLE IF NOT EXISTS documents
(
    id          UUID         NOT NULL,
    file_name   VARCHAR(500) NOT NULL,
    file_size   BIGINT,
    chunk_count INTEGER      NOT NULL DEFAULT 0,
    topic       VARCHAR(100),
    status      VARCHAR(30)  NOT NULL DEFAULT 'PROCESSING',
    file_id     VARCHAR(100),            -- matches metadata.file_id stored in Qdrant
    uploaded_by UUID         REFERENCES users (id) ON DELETE SET NULL,
    uploaded_at TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,

    CONSTRAINT pk_documents PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);
CREATE INDEX IF NOT EXISTS idx_documents_file_id ON documents (file_id);

-- ─── 2. Crawl Sources (URL sources to be crawled) ────────────────────────────
CREATE TABLE IF NOT EXISTS crawl_sources
(
    id             UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    base_url       VARCHAR(512) NOT NULL,
    cron_schedule  VARCHAR(100)          DEFAULT '0 0 6 * * *',
    max_depth      INTEGER      NOT NULL DEFAULT 3,
    status         VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    last_crawl_at  TIMESTAMP,
    pages_crawled  INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,

    CONSTRAINT pk_crawl_sources PRIMARY KEY (id),
    CONSTRAINT uq_crawl_sources_url UNIQUE (base_url)
);

-- ─── 3. Crawler Pages (crawled HTML pages pending admin review) ───────────────
CREATE TABLE IF NOT EXISTS crawler_pages
(
    id          UUID         NOT NULL,
    source_id   UUID         REFERENCES crawl_sources (id) ON DELETE CASCADE,
    title       VARCHAR(500),
    url         VARCHAR(512) NOT NULL,
    topic_tag   VARCHAR(100),
    word_count  INTEGER      NOT NULL DEFAULT 0,
    chunk_count INTEGER      NOT NULL DEFAULT 0,
    status      VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    crawled_at  TIMESTAMP    NOT NULL,
    reviewed_at TIMESTAMP,
    reviewed_by UUID         REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT pk_crawler_pages PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_crawler_pages_status    ON crawler_pages (status);
CREATE INDEX IF NOT EXISTS idx_crawler_pages_source_id ON crawler_pages (source_id);

-- ─── 4. Job Executions (history of every background job run) ─────────────────
CREATE TABLE IF NOT EXISTS job_executions
(
    id          UUID         NOT NULL,
    job_id      VARCHAR(100) NOT NULL,    -- logical job identifier (e.g. "crawl-all")
    job_name    VARCHAR(255) NOT NULL,
    job_type    VARCHAR(30)  NOT NULL DEFAULT 'SCHEDULED',
    status      VARCHAR(30)  NOT NULL,
    duration_ms BIGINT,
    pages_count INTEGER,
    error_msg   TEXT,
    started_at  TIMESTAMP    NOT NULL,
    finished_at TIMESTAMP,

    CONSTRAINT pk_job_executions PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_job_executions_job_id  ON job_executions (job_id);
CREATE INDEX IF NOT EXISTS idx_job_executions_started ON job_executions (started_at DESC);
