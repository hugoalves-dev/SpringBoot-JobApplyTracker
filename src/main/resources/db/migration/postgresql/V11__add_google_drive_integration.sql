CREATE TABLE google_drive_connections (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    google_account_id VARCHAR(255) NOT NULL,
    google_email VARCHAR(255) NOT NULL,
    google_display_name VARCHAR(255) NULL,
    access_token VARCHAR(4096) NOT NULL,
    refresh_token VARCHAR(4096) NOT NULL,
    access_token_expires_at TIMESTAMP NOT NULL,
    granted_scopes VARCHAR(2048) NOT NULL,
    root_folder_id VARCHAR(255) NULL,
    root_folder_name VARCHAR(255) NULL,
    connected_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_google_drive_connections PRIMARY KEY (id),
    CONSTRAINT uk_google_drive_connections_user UNIQUE (user_id),
    CONSTRAINT fk_google_drive_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_gdrive_connection_google_account ON google_drive_connections (google_account_id);

CREATE TABLE google_drive_base_resumes (
    id UUID NOT NULL,
    connection_id UUID NOT NULL,
    google_file_id VARCHAR(255) NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    web_view_link VARCHAR(2048) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_google_drive_base_resumes PRIMARY KEY (id),
    CONSTRAINT uk_google_drive_base_resumes_connection_file UNIQUE (connection_id, google_file_id),
    CONSTRAINT fk_google_drive_base_resumes_connection FOREIGN KEY (connection_id) REFERENCES google_drive_connections (id) ON DELETE CASCADE
);

CREATE INDEX idx_gdrive_base_resume_connection ON google_drive_base_resumes (connection_id);

CREATE TABLE google_drive_oauth_states (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    state_token VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_google_drive_oauth_states PRIMARY KEY (id),
    CONSTRAINT uk_google_drive_oauth_states_state UNIQUE (state_token),
    CONSTRAINT fk_google_drive_oauth_states_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_gdrive_oauth_states_user ON google_drive_oauth_states (user_id);
CREATE INDEX idx_gdrive_oauth_states_expires ON google_drive_oauth_states (expires_at);

