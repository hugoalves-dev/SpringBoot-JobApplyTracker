CREATE TABLE IF NOT EXISTS webauthn_credentials (
    id             UUID   NOT NULL PRIMARY KEY,
    user_id        UUID   NOT NULL,
    credential_id  VARCHAR(512) NOT NULL,
    public_key_cose TEXT        NOT NULL,
    sign_count     BIGINT       NOT NULL DEFAULT 0,
    transports     VARCHAR(255),
    user_handle    VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    CONSTRAINT uk_webauthn_credential_id UNIQUE (credential_id),


    CONSTRAINT fk_webauthn_credentials_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS webauthn_challenges (
    id           UUID   NOT NULL PRIMARY KEY,
    user_id      UUID,
    type         VARCHAR(32)  NOT NULL,
    request_json TEXT         NOT NULL,
    challenge    VARCHAR(512) NOT NULL,
    used         BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at   TIMESTAMP     NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL,


    CONSTRAINT fk_webauthn_challenges_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_webauthn_credentials_user_id ON webauthn_credentials (user_id);
CREATE INDEX idx_webauthn_credentials_credential_id ON webauthn_credentials (credential_id);
CREATE INDEX idx_webauthn_challenges_user_id ON webauthn_challenges (user_id);
CREATE INDEX idx_webauthn_challenges_expires_at ON webauthn_challenges (expires_at);

