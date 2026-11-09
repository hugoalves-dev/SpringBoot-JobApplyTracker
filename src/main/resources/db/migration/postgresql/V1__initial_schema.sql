-- V1: Initial schema

CREATE TABLE IF NOT EXISTS users (
    id            UUID   NOT NULL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID   NOT NULL PRIMARY KEY,
    token       VARCHAR(512) NOT NULL,
    expiry_date TIMESTAMP     NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    user_id     UUID   NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_refresh_tokens_token UNIQUE (token),


    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          UUID   NOT NULL PRIMARY KEY,
    token       VARCHAR(512) NOT NULL,
    expiry_date TIMESTAMP     NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    user_id     UUID   NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    CONSTRAINT uk_prt_token UNIQUE (token),


    CONSTRAINT fk_prt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS job_applications (
    id                           UUID    NOT NULL PRIMARY KEY,
    vacancy_name                 VARCHAR(255)  NOT NULL,
    recruiter_name               VARCHAR(255),
    vacancy_opened_by            VARCHAR(255)  NOT NULL,
    vacancy_link                 VARCHAR(2048),
    application_date             DATE,
    rh_accepted_connection       BOOLEAN       NOT NULL DEFAULT FALSE,
    interview_scheduled          BOOLEAN       NOT NULL DEFAULT FALSE,
    next_step_date_time          TIMESTAMP,
    status                       VARCHAR(100)  NOT NULL,
    recruiter_dm_reminder_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    user_id                      UUID    NOT NULL,
    created_at                   TIMESTAMP      NOT NULL,
    updated_at                   TIMESTAMP      NOT NULL,




    CONSTRAINT fk_job_applications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_email ON users (email);
CREATE INDEX idx_refresh_token_value ON refresh_tokens (token);
CREATE INDEX idx_refresh_token_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_prt_token ON password_reset_tokens (token);
CREATE INDEX idx_prt_user_id ON password_reset_tokens (user_id);
CREATE INDEX idx_app_user_id ON job_applications (user_id);
CREATE INDEX idx_app_status ON job_applications (status);
CREATE INDEX idx_app_next_step ON job_applications (next_step_date_time);
CREATE INDEX idx_app_application_date ON job_applications (application_date);

