-- Safe migration to rename column `vacancy_opened_by` to `organization` in MariaDB
-- Step 1: Create the new column
ALTER TABLE job_applications
  ADD COLUMN organization VARCHAR(255) NOT NULL DEFAULT '';

-- Step 2: Copy data from old column to new column
UPDATE job_applications
  SET organization = vacancy_opened_by;

-- Step 3: Drop the old column
ALTER TABLE job_applications
  DROP COLUMN vacancy_opened_by;

