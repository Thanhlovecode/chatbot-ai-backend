-- ============================================================
-- V5: Add missing created_at column to admin tables
--     BaseEntity maps @CreatedDate -> created_at for all entities.
--     V4 omitted this column from job_executions, documents,
--     and crawler_pages tables.
-- ============================================================

-- job_executions: backfill with started_at (closest semantic equivalent)
ALTER TABLE job_executions
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();

UPDATE job_executions
SET created_at = started_at
WHERE created_at = NOW();

-- documents: backfill with uploaded_at
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();

UPDATE documents
SET created_at = uploaded_at
WHERE created_at = NOW();

-- crawler_pages: backfill with crawled_at
ALTER TABLE crawler_pages
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();

UPDATE crawler_pages
SET created_at = crawled_at
WHERE created_at = NOW();
