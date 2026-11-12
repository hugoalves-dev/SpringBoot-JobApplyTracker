ALTER TABLE google_drive_base_resumes
    -- ISO 639-1 (e.g. EN, PT) or locale variant (e.g. en-US); max 10 chars
    ADD COLUMN language VARCHAR(10) NULL,
    ADD COLUMN is_template BOOLEAN NOT NULL DEFAULT FALSE;

