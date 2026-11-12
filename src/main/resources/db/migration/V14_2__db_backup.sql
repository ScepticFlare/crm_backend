-- On-demand database backups started by an ADMIN from the CRM ("Take Backup").
-- Purely additive and safe to re-run (IF NOT EXISTS guards): no existing table
-- or row is touched.
--
--  * db_backup: one row per backup attempt - who started it, when, and how it
--    ended. The dump itself (pg_dump custom format of the public schema) lives
--    in a PRIVATE Supabase Storage bucket, never in this database and never on
--    a public URL; object_key names that object, removed_at is set once the
--    retention policy has deleted it. error_message holds a short, scrubbed,
--    user-facing reason only (no host, user name, password or connection URL).
--  * uq_db_backup_one_running: at most one RUNNING backup at a time, enforced
--    by the database as well as by the application.
--
-- Version 14.2 sorts after 14.1 (employee mailboxes) and before the WhatsApp
-- migrations 15-17, so it must be deployed before those (see docs/backup.md).

CREATE TABLE IF NOT EXISTS db_backup (
    id                        BIGSERIAL     PRIMARY KEY,
    status                    VARCHAR(20)   NOT NULL,
    initiated_by_employee_id  BIGINT        NULL REFERENCES employees (id) ON DELETE SET NULL,
    initiated_by_name         VARCHAR(255)  NOT NULL,
    started_at                TIMESTAMP     NOT NULL,
    finished_at               TIMESTAMP     NULL,
    size_bytes                BIGINT        NULL,
    sha256                    VARCHAR(64)   NULL,
    object_key                VARCHAR(200)  NULL,
    table_count               INTEGER       NULL,
    server_version            VARCHAR(60)   NULL,
    pg_dump_version           VARCHAR(60)   NULL,
    error_message             VARCHAR(500)  NULL,
    removed_at                TIMESTAMP     NULL,
    CONSTRAINT chk_db_backup_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_db_backup_one_running ON db_backup (status) WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS idx_db_backup_started_at ON db_backup (started_at DESC);
