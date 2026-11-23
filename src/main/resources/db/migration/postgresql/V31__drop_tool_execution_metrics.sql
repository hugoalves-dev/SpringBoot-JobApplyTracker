-- MCP operation auditing moved from DB persistence to Micrometer/Prometheus metrics
-- plus structured DEBUG logs (see McpAuditAspect / McpAuditService). The DB-backed
-- tool_execution_metrics table (created in V26) and its REST analytics are no longer used.
-- Indexes are dropped with the table.
DROP TABLE IF EXISTS tool_execution_metrics;

