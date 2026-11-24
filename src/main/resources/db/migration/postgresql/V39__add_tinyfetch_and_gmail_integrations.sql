-- Issue #113: per-user TinyFish Fetch and native Gmail draft integrations.
-- Secrets (API keys, OAuth tokens) are stored as AES-GCM ciphertext produced by the application.

CREATE TABLE tinyfetch_settings (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    api_key_encrypted VARCHAR(2048) NOT NULL,
    api_key_hint VARCHAR(16) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    per_minute_limit INT NOT NULL,
    daily_limit INT NOT NULL,
    last_tested_at TIMESTAMP(6) NULL,
    last_test_success BOOLEAN NULL,
    last_test_message VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_tinyfetch_settings PRIMARY KEY (id),
    CONSTRAINT uk_tinyfetch_settings_user UNIQUE (user_id),
    CONSTRAINT fk_tinyfetch_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE tinyfetch_daily_usage (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    usage_date DATE NOT NULL,
    fetch_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_tinyfetch_daily_usage PRIMARY KEY (id),
    CONSTRAINT uk_tinyfetch_usage_user_day UNIQUE (user_id, usage_date),
    CONSTRAINT fk_tinyfetch_usage_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE gmail_accounts (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    email VARCHAR(320) NOT NULL,
    profile_name VARCHAR(60) NOT NULL,
    access_token_encrypted VARCHAR(8192) NOT NULL,
    refresh_token_encrypted VARCHAR(4096) NOT NULL,
    access_token_expires_at TIMESTAMP(6) NOT NULL,
    granted_scopes VARCHAR(2048) NOT NULL,
    reauthorization_required BOOLEAN NOT NULL DEFAULT FALSE,
    last_tested_at TIMESTAMP(6) NULL,
    connected_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_gmail_accounts PRIMARY KEY (id),
    CONSTRAINT uk_gmail_accounts_user_email UNIQUE (user_id, email),
    CONSTRAINT uk_gmail_accounts_user_profile UNIQUE (user_id, profile_name),
    CONSTRAINT fk_gmail_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE gmail_oauth_states (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    state_token VARCHAR(255) NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    profile_name VARCHAR(60) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_gmail_oauth_states PRIMARY KEY (id),
    CONSTRAINT uk_gmail_oauth_state UNIQUE (state_token),
    CONSTRAINT fk_gmail_oauth_states_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_gmail_oauth_expires ON gmail_oauth_states (expires_at);

CREATE TABLE email_draft_requests (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    gmail_account_id UUID NOT NULL,
    application_id UUID NULL,
    status VARCHAR(20) NOT NULL,
    gmail_draft_id VARCHAR(255) NULL,
    gmail_message_id VARCHAR(255) NULL,
    error_message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_email_draft_requests PRIMARY KEY (id),
    CONSTRAINT uk_email_draft_requests_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_email_draft_requests_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
