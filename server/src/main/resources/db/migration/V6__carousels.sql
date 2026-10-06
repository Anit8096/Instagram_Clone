-- Carousels: a post has 1–10 media items in order (post_media) instead of one posts.media_id.
-- Also prepares video and reels (M14): media type/duration/status, post kind/status. Every existing row keeps its
-- meaning: photos that are ready, published regular posts.

ALTER TABLE media ADD COLUMN type VARCHAR(8) NOT NULL DEFAULT 'photo' CHECK (type IN ('photo', 'video'));
ALTER TABLE media ADD COLUMN duration_ms INT CHECK (duration_ms > 0);
ALTER TABLE media ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ready'
    CHECK (status IN ('pending', 'processing', 'ready', 'failed'));

CREATE TABLE post_media (
    post_id  UUID NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    position INT  NOT NULL CHECK (position BETWEEN 0 AND 9),
    -- A media item belongs to at most one post (replaces the posts_media_unique index). CASCADE because deleting a
    -- user cascades to both media and posts, and a plain reference would be checked before the posts are gone.
    media_id UUID NOT NULL UNIQUE REFERENCES media (id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, position)
);

INSERT INTO post_media (post_id, position, media_id) SELECT id, 0, media_id FROM posts;

-- Drops posts_media_unique with it.
ALTER TABLE posts DROP COLUMN media_id;

ALTER TABLE posts ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'post' CHECK (kind IN ('post', 'reel'));
ALTER TABLE posts ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'published'
    CHECK (status IN ('processing', 'published', 'failed'));
