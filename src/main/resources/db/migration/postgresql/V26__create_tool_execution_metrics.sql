CREATE TABLE tool_execution_metrics (
    id           UUID   NOT NULL,
    tool_name    VARCHAR(255) NOT NULL,
    execution_time_ms BIGINT  NOT NULL,
    request_bytes     INT     NOT NULL,
    response_bytes    INT     NOT NULL,
    request_tokens    INT     NOT NULL,
    response_tokens   INT     NOT NULL,
    total_tokens      INT     NOT NULL,
    expensive         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_tool_execution_metrics PRIMARY KEY (id)
);

CREATE INDEX idx_tool_metrics_tool_name ON tool_execution_metrics (tool_name);
CREATE INDEX idx_tool_metrics_created_at ON tool_execution_metrics (created_at);
CREATE INDEX idx_tool_metrics_expensive ON tool_execution_metrics (expensive);

