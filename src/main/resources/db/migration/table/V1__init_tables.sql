CREATE TABLE IF NOT EXISTS chat_sessions
(
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    title      VARCHAR(500),
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,

    CONSTRAINT pk_chat_sessions PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS chat_messages
(
    id         UUID         NOT NULL,
    message_id VARCHAR(50)  NOT NULL,
    session_id UUID         NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    content    TEXT         NOT NULL,
    model      VARCHAR(50),
    created_at TIMESTAMP    NOT NULL,

    CONSTRAINT pk_chat_messages PRIMARY KEY (id),

    CONSTRAINT uq_message_id
        UNIQUE (message_id),

    CONSTRAINT fk_message_session
        FOREIGN KEY (session_id)
            REFERENCES chat_sessions (id)
            ON DELETE CASCADE
);