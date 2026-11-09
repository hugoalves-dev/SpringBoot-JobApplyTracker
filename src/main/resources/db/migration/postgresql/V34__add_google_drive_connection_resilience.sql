ALTER TABLE google_drive_connections
    ADD COLUMN reauthorization_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reauthorization_reason VARCHAR(255) NULL;

