CREATE TABLE google_drive_base_information (
    id UUID NOT NULL,
    connection_id UUID NOT NULL,
    google_file_id VARCHAR(255) NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    doc_type VARCHAR(20) NOT NULL,
    web_view_link VARCHAR(2048) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_google_drive_base_information PRIMARY KEY (id),
    CONSTRAINT uk_google_drive_base_information_connection_file UNIQUE (connection_id, google_file_id),
    CONSTRAINT fk_google_drive_base_information_connection FOREIGN KEY (connection_id) REFERENCES google_drive_connections (id) ON DELETE CASCADE
);

CREATE INDEX idx_gdrive_base_info_connection ON google_drive_base_information (connection_id);
CREATE INDEX idx_gdrive_base_info_file ON google_drive_base_information (google_file_id);

