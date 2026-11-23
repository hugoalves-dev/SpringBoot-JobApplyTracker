ALTER TABLE users
  ADD COLUMN privacy_policy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN privacy_policy_accepted_at TIMESTAMP NULL;

