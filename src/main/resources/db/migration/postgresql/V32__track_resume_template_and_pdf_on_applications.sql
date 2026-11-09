ALTER TABLE job_applications
    ADD COLUMN drive_resume_template_id UUID NULL,
    ADD COLUMN drive_resume_pdf_file_id VARCHAR(255) NULL,
    ADD COLUMN drive_resume_pdf_url VARCHAR(2048) NULL;

