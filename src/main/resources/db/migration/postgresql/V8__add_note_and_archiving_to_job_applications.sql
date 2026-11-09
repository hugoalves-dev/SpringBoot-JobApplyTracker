-- V8: Add note and archiving fields to job applications

ALTER TABLE job_applications
    ADD COLUMN note TEXT NULL,
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived_at TIMESTAMP NULL;

CREATE INDEX idx_app_archived ON job_applications (archived);

