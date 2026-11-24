CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_pending_created_at
    ON outbox (created_at ASC, id ASC) INCLUDE (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_quarantined_at
    ON outbox (quarantined_at DESC, id DESC)
    WHERE status = 'QUARANTINED';
