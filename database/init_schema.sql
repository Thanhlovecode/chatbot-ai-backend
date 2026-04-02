-- Spring AI Chat Memory Database Schema
-- PostgreSQL 16+

-- Create database (run this separately if needed)
-- CREATE DATABASE spring_ai_db;

-- Connect to database
\c spring_ai_db;

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Drop existing tables if they exist (for fresh start)
DROP TABLE IF EXISTS chat_messages CASCADE;
DROP TABLE IF EXISTS chat_sessions CASCADE;

-- Create chat_sessions table
CREATE TABLE chat_sessions (
    id VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id VARCHAR(255) NOT NULL,
    title VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Create chat_messages table
CREATE TABLE chat_messages (
    id VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    session_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    model VARCHAR(100),
    CONSTRAINT fk_session FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

-- Create indexes for better query performance
CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_chat_sessions_updated_at ON chat_sessions(updated_at DESC);
CREATE INDEX idx_chat_sessions_user_active ON chat_sessions(user_id, active);

CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX idx_chat_messages_created_at ON chat_messages(created_at);
CREATE INDEX idx_chat_messages_session_created ON chat_messages(session_id, created_at);

-- Create a function to automatically update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create trigger to auto-update updated_at on chat_sessions
CREATE TRIGGER update_chat_sessions_updated_at
    BEFORE UPDATE ON chat_sessions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Insert sample data (optional)
INSERT INTO chat_sessions (id, user_id, title, active)
VALUES
    ('sample-session-1', 'user123', 'Sample Chat About AI', TRUE),
    ('sample-session-2', 'user123', 'Another Conversation', TRUE),
    ('sample-session-3', 'user456', 'User 2 Chat', TRUE);

INSERT INTO chat_messages (session_id, role, content, model)
VALUES
    ('sample-session-1', 'USER', 'What is artificial intelligence?', NULL),
    ('sample-session-1', 'ASSISTANT', 'Artificial Intelligence (AI) is the simulation of human intelligence processes by machines, especially computer systems.', 'gemini-2.5-flash'),
    ('sample-session-1', 'USER', 'Tell me more about machine learning', NULL),
    ('sample-session-1', 'ASSISTANT', 'Machine Learning is a subset of AI that enables systems to learn and improve from experience without being explicitly programmed.', 'gemini-2.5-flash');

-- Verify tables created
\dt

-- Show sample data
SELECT
    s.id,
    s.user_id,
    s.title,
    s.created_at,
    COUNT(m.id) as message_count
FROM chat_sessions s
LEFT JOIN chat_messages m ON s.id = m.session_id
GROUP BY s.id, s.user_id, s.title, s.created_at
ORDER BY s.created_at DESC;

COMMIT;

