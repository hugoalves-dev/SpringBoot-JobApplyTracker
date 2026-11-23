ALTER TABLE gpt_oauth_authorization_codes ALTER COLUMN code_challenge DROP NOT NULL;
ALTER TABLE gpt_oauth_authorization_codes ALTER COLUMN code_challenge_method DROP NOT NULL;
