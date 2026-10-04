-- At most one "liked your post" per (actor, post) and one "started following you" per (actor, recipient):
-- like→unlike→like or follow→unfollow→follow spam collapses into a single row (INSERT … ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX notifications_like_once ON notifications (actor_id, post_id) WHERE type = 'like';
CREATE UNIQUE INDEX notifications_follow_once ON notifications (actor_id, recipient_id) WHERE type = 'follow';

-- Unread badge count.
CREATE INDEX notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;
