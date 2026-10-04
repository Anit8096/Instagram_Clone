-- A media item backs at most one post; makes concurrent double-posting of one upload fail cleanly.
CREATE UNIQUE INDEX posts_media_unique ON posts (media_id);

CREATE INDEX media_owner ON media (owner_id);
