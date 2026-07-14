-- Idempotency guard: one row per inbound event ID, checked before any notification is created.
CREATE TABLE IF NOT EXISTS processed_event (
    event_id        VARCHAR(128) PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Notification records. Source of truth for both history GET and read/unread state.
CREATE TABLE IF NOT EXISTS notification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(128) NOT NULL,
    event_id        VARCHAR(128) NOT NULL REFERENCES processed_event(event_id),
    notice_type     VARCHAR(64)  NOT NULL,
    title           VARCHAR(256) NOT NULL,
    message         TEXT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_notification_user_unread
    ON notification (user_id, is_read, created_at DESC);

-- Email delivery attempts, so failures are recorded rather than silently dropped
-- (see EmailNotifier / RestEmailClient - this is the audit/retry trail).
CREATE TABLE IF NOT EXISTS email_delivery_attempt (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notification(id),
    attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          VARCHAR(32) NOT NULL, -- SENT | FAILED | RETRYING | DEAD_LETTERED
    error_detail    TEXT
);

-- NOTE: NOTIFY is issued explicitly from application code (NotificationService),
-- in the same transaction as the insert above - not via a DB trigger/function.
-- See NotificationService.handleIncomingEvent().
