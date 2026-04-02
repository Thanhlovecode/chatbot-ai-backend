-- Create users table
CREATE TABLE IF NOT EXISTS users
(
    id           UUID         NOT NULL,
    email        VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    avatar_url   VARCHAR(512),
    google_id    VARCHAR(255),
    role         VARCHAR(20)  NOT NULL DEFAULT 'USER',
    active       BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMP    NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_google_id UNIQUE (google_id)
);

-- Create index on email for fast lookup during login
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);

-- Add FK from chat_sessions.user_id to users.id
-- Note: Existing orphan sessions will be deleted first
DELETE FROM chat_sessions WHERE user_id NOT IN (SELECT id FROM users);

ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_session_user
        FOREIGN KEY (user_id) REFERENCES users (id)
            ON DELETE CASCADE;
