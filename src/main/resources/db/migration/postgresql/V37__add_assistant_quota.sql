-- Ask ApplyWell quota: one row per authorized request slot (see AssistantReservationStatus).
CREATE TABLE assistant_request_reservations (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    CONSTRAINT pk_assistant_request_reservations PRIMARY KEY (id),
    CONSTRAINT fk_assistant_reservations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_assistant_reservations_user_created ON assistant_request_reservations (user_id, created_at);
CREATE INDEX idx_assistant_reservations_created ON assistant_request_reservations (created_at);

-- Row locked FOR UPDATE to serialize quota checks across application instances.
CREATE TABLE assistant_quota_locks (
    lock_name VARCHAR(64) NOT NULL,
    CONSTRAINT pk_assistant_quota_locks PRIMARY KEY (lock_name)
);

INSERT INTO assistant_quota_locks (lock_name) VALUES ('assistant-global');
