package com.jobtracker.mcp.resources;

import com.jobtracker.mcp.McpResourcesConfig;
import com.jobtracker.mcp.audit.AuditMcpOperation;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.Role;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpResource.McpAnnotations;
import org.springframework.stereotype.Service;

@Service
public class McpApplicationLifecycleRulesResource {

    private static final String LAST_MODIFIED = "2026-08-13";

    @McpResource(
            uri = McpResourcesConfig.URI_APPLICATION_LIFECYCLE_RULES,
            name = "Application Lifecycle Rules",
            title = "Application Lifecycle Rules",
            description = "Mandatory rules separating application status, archival, restoration, and deletion.",
            mimeType = "text/markdown",
            annotations = @McpAnnotations(
                    audience = {Role.ASSISTANT},
                    lastModified = LAST_MODIFIED,
                    priority = 1.0d))
    @AuditMcpOperation(action = "Application Lifecycle Rules")
    public String applicationLifecycleRules(McpSyncServerExchange exchange) {
        return """
                # Job application lifecycle rules

                The application status and the `archived` flag represent different concepts and MUST be handled independently.

                ## Status semantics

                - `Rejected`: the company or recruiter rejected the candidate or decided not to continue the hiring process.
                - `Approved`: the candidate was approved.
                - Other statuses represent the current stage of an active hiring process.
                - Changing an application to `Rejected` or `Approved` MUST NOT archive it.

                When the user says `rejected`, `retorno negativo`, `não avançou`, `não passou`, or `empresa recusou`:

                1. Call `Update-Application-Status` with `Rejected` after obtaining the valid status value from `List-Statuses`.
                2. Keep `archived = false`.
                3. Do not call `Archive-Application` unless the user separately and explicitly requests archival.

                When the user says `dar baixa` while providing or showing a rejection message, interpret it as a status-only
                update to `Rejected`, not as an archive request.

                ## Archive semantics

                Archiving is a soft-delete operation used only when the candidate no longer intends to proceed with the
                application record, for example:

                - the candidate decided not to apply;
                - the candidate withdrew from the process;
                - the vacancy is incompatible and the candidate abandoned the application;
                - the record is a duplicate, test, invalid, or obsolete entry;
                - the user explicitly asks to archive the application.

                An archived application is hidden from the normal active list but preserved for historical purposes.
                Preserve its current status when archiving.

                ## Mandatory behavior

                - Never infer that a terminal status requires archiving.
                - Never call `Archive-Application` after setting `Rejected` or `Approved` unless the user explicitly requested archival.
                - When archival intent is ambiguous, do not archive. Ask for clarification when clarification is necessary.
                - Always apply the least destructive mutation necessary to fulfill the request.
                - Use `Restore-Application`, or `Patch-Application` with `archived = false`, to make an archived record active again.
                  Restoration preserves the current status and all other data.
                - Use `Patch-Application` for any partial change. It updates only the fields you send, so never resend unrelated
                  fields or boolean flags to satisfy a schema.
                - Interview counts belong to each application. Changing status or scheduling an interview does not increment them.
                - After confirming attendance with the user, use `Patch-Application` with `interviewCount` set to the absolute
                  total of completed interviews (integer >= 0). Omit it to preserve the count. Repeating a total is idempotent.
                - Scheduled, cancelled or missed interviews and asynchronous technical tests are not completed interviews.
                - Do not infer counts from free-text notes or use gamification events to record interviews.
                - Permanent deletion is allowed only when the user explicitly requests permanent deletion.
                - Do not delete and recreate an application to change its archive state without explicit user approval.

                ## Examples

                User: `A empresa não avançou, dá baixa.`
                Action: set status to `Rejected`. Do not archive.

                User: `Desisti dessa vaga, pode arquivar.`
                Action: archive the application. Preserve its current status.

                User: `Essa candidatura foi criada por engano.`
                Action: archive it. Delete it only if the user explicitly requests permanent deletion.

                User: `Recebi uma rejeição.`
                Action: set status to `Rejected`. Keep the record visible and non-archived.

                User: `Arquivei por engano, restaure.`
                Action: call `Restore-Application`, or `Patch-Application` with `archived = false`. Preserve the current status.

                User: `Essa vaga rejeitada deveria estar na lista normal.`
                Action: call `Patch-Application` with `archived = false` only. Do not change the status.
                """;
    }
}
