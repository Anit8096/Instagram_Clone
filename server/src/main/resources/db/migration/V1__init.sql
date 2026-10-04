-- Insta v1 schema. All ids are UUIDs; client-generated where writes must be idempotent
-- (comments, messages). Usernames and emails are stored lower-cased by the application.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    username        VARCHAR(30)  NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._]{3,30}$'),
    email           VARCHAR(254) UNIQUE,
    password_hash   TEXT,
    google_sub      VARCHAR(255) UNIQUE,
    display_name    VARCHAR(60)  NOT NULL DEFAULT '',
    bio             VARCHAR(150) NOT NULL DEFAULT '',
    avatar_media_id UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (password_hash IS NOT NULL OR google_sub IS NOT NULL)
);
CREATE INDEX users_username_trgm ON users USING gin (username gin_trgm_ops);
CREATE INDEX users_display_name_trgm ON users USING gin (display_name gin_trgm_ops);

-- Refresh tokens rotate on every use. All tokens descended from one login share a family_id,
-- so presenting an already-rotated token revokes the whole family (theft detection).
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE media (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN ('post', 'avatar')),
    full_path   VARCHAR(512) NOT NULL,
    thumb_path  VARCHAR(512) NOT NULL,
    width       INT          NOT NULL,
    height      INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE users
    ADD CONSTRAINT users_avatar_fk FOREIGN KEY (avatar_media_id) REFERENCES media (id) ON DELETE SET NULL;

CREATE TABLE posts (
    id            UUID PRIMARY KEY,
    author_id     UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    media_id      UUID          NOT NULL REFERENCES media (id),
    caption       VARCHAR(2200) NOT NULL DEFAULT '',
    like_count    INT           NOT NULL DEFAULT 0,
    comment_count INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX posts_author_created ON posts (author_id, created_at DESC, id DESC);
CREATE INDEX posts_created ON posts (created_at DESC, id DESC);

CREATE TABLE follows (
    follower_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
CREATE INDEX follows_followee ON follows (followee_id);

CREATE TABLE likes (
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id    UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);
CREATE INDEX likes_post_created ON likes (post_id, created_at);

CREATE TABLE comments (
    id         UUID PRIMARY KEY,
    post_id    UUID          NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    author_id  UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body       VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX comments_post_created ON comments (post_id, created_at, id);

-- 1:1 conversations; user_a < user_b keeps each pair unique regardless of who started it.
CREATE TABLE conversations (
    id         UUID PRIMARY KEY,
    user_a     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_b     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_a, user_b),
    CHECK (user_a < user_b)
);
CREATE INDEX conversations_user_b ON conversations (user_b);

CREATE TABLE messages (
    id              UUID PRIMARY KEY,
    conversation_id UUID          NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body            VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX messages_conversation_created ON messages (conversation_id, created_at DESC, id DESC);

CREATE TABLE conversation_reads (
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    recipient_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(16) NOT NULL CHECK (type IN ('like', 'comment', 'follow', 'message')),
    post_id      UUID REFERENCES posts (id) ON DELETE CASCADE,
    comment_id   UUID REFERENCES comments (id) ON DELETE CASCADE,
    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notifications_recipient_created ON notifications (recipient_id, created_at DESC, id DESC);

CREATE TABLE device_tokens (
    fcm_token  VARCHAR(512) PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX device_tokens_user ON device_tokens (user_id);
