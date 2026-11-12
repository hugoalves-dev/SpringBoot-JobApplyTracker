ALTER TABLE job_applications
    ADD COLUMN drive_resume_file_id VARCHAR(255) NULL,
    ADD COLUMN drive_resume_file_name VARCHAR(255) NULL,
    ADD COLUMN drive_resume_document_url VARCHAR(2048) NULL,
    ADD COLUMN drive_resume_generated_at TIMESTAMP NULL;

