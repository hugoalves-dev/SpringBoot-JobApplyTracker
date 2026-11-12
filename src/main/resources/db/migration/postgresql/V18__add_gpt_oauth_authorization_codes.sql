CREATE TABLE IF NOT EXISTS gpt_oauth_authorization_codes (
    id                    UUID   NOT NULL PRIMARY KEY,
    user_id               UUID   NOT NULL,
    client_id             VARCHAR(255) NOT NULL,
    redirect_uri          VARCHAR(500) NOT NULL,
    scope_value           VARCHAR(1000) NOT NULL,
    code_hash             VARCHAR(128) NOT NULL,
    code_challenge        VARCHAR(255) NOT NULL,
    code_challenge_method VARCHAR(20)  NOT NULL,
    expires_at            TIMESTAMP     NOT NULL,
    used_at               TIMESTAMP     NULL,
    created_at            TIMESTAMP     NOT NULL,
    CONSTRAINT uk_gpt_oauth_code_hash UNIQUE (code_hash),


    CONSTRAINT fk_gpt_oauth_code_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_gpt_oauth_code_user ON gpt_oauth_authorization_codes (user_id);
CREATE INDEX idx_gpt_oauth_code_expires ON gpt_oauth_authorization_codes (expires_at);

