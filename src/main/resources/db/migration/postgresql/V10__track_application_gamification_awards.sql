ALTER TABLE job_applications
    ADD COLUMN application_created_xp_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN recruiter_dm_xp_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN interview_progress_xp_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN note_added_xp_awarded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN offer_won_xp_awarded BOOLEAN NOT NULL DEFAULT FALSE;

