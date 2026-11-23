CREATE TABLE user_interview_metrics (
    user_id UUID NOT NULL,
    interview_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_interview_metrics PRIMARY KEY (user_id),
    CONSTRAINT fk_user_interview_metrics_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE interview_events (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    old_status VARCHAR(100) NULL,
    new_status VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_interview_events PRIMARY KEY (id),
    CONSTRAINT fk_interview_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_interview_events_application FOREIGN KEY (application_id) REFERENCES job_applications (id) ON DELETE CASCADE
);

CREATE INDEX idx_interview_events_user_id ON interview_events (user_id);
CREATE INDEX idx_interview_events_application_id ON interview_events (application_id);

