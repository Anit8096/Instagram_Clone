-- Background jobs (transactional outbox). Postgres is the source of truth: services insert a row in the same
-- transaction as their data, and after the commit the job id is published to a Redis stream for the workers.
-- A reconciler re-publishes due rows, so jobs survive Redis outages and restarts.
CREATE TABLE jobs (
    id            UUID PRIMARY KEY,
    type          VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL DEFAULT '{}',
    status        VARCHAR(16)  NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'dead')),
    attempts      INT          NOT NULL DEFAULT 0,
    max_attempts  INT          NOT NULL CHECK (max_attempts > 0),
    -- Not before this time (delayed jobs and retry backoff).
    run_at        TIMESTAMPTZ  NOT NULL,
    -- At most one queued or running job per key (recurring jobs, "expire story X").
    dedupe_key    VARCHAR(128),
    -- Last time the id was published to Redis; the reconciler re-publishes rows nobody picked up.
    dispatched_at TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX jobs_due ON jobs (run_at) WHERE status = 'queued';
CREATE INDEX jobs_running ON jobs (updated_at) WHERE status = 'running';
CREATE INDEX jobs_finished ON jobs (updated_at) WHERE status IN ('done', 'dead');
CREATE UNIQUE INDEX jobs_dedupe_active ON jobs (dedupe_key) WHERE status IN ('queued', 'running');
