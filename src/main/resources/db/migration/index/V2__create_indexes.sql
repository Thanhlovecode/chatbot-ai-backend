CREATE INDEX idx_session_user_cursor
    ON chat_sessions (user_id, updated_at DESC, id DESC)
    WHERE active = true;

CREATE INDEX idx_msg_session_cursor
    ON chat_messages (session_id, created_at DESC, id DESC);

