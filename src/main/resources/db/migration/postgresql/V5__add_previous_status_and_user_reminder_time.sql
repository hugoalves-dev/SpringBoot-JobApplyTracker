ALTER TABLE job_applications
    ADD COLUMN previous_status VARCHAR(100) NULL;

ALTER TABLE users
    ADD COLUMN reminder_time TIME NOT NULL DEFAULT '19:00:00';

