package com.jobtracker.unit.mcp;

import com.jobtracker.dto.integrations.CreateEmailDraftRequest;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.mcp.audit.AuditMcpOperation;
import com.jobtracker.mcp.tools.McpIntegrationTools;
import com.jobtracker.service.integrations.gmail.GmailAccountService;
import com.jobtracker.service.integrations.gmail.GmailDraftService;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpIntegrationToolsTest {

    private final TinyFetchService tinyFetchService = mock(TinyFetchService.class);
    private final GmailAccountService accountService = mock(GmailAccountService.class);
    private final GmailDraftService draftService = mock(GmailDraftService.class);
    private final McpIntegrationTools tools = new McpIntegrationTools(tinyFetchService, accountService, draftService);

    @Test
    void exposesAuditedToolsWithSafetyHints() {
        Map<String, Method> byName = Arrays.stream(McpIntegrationTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .collect(Collectors.toMap(m -> m.getAnnotation(McpTool.class).name(), m -> m));

        assertThat(byName).containsOnlyKeys("Fetch-Url", "List-Email-Accounts", "Create-Email-Draft");
        byName.values().forEach(m -> assertThat(m.isAnnotationPresent(AuditMcpOperation.class)).isTrue());

        McpTool fetch = byName.get("Fetch-Url").getAnnotation(McpTool.class);
        assertThat(fetch.annotations().readOnlyHint()).isTrue();
        assertThat(fetch.description()).contains("UNTRUSTED").contains("manualInputRequired");

        McpTool draft = byName.get("Create-Email-Draft").getAnnotation(McpTool.class);
        assertThat(draft.annotations().destructiveHint()).isFalse();
        assertThat(draft.description()).contains("never sent").contains("NOT marked as applied");
    }

    @Test
    void fetchDelegatesWithCacheFlag() {
        tools.fetchUrl(null, "https://jobs.example.com/1", null);
        verify(tinyFetchService).fetch("https://jobs.example.com/1", false);
    }

    @Test
    void createDraftMapsArguments() {
        UUID applicationId = UUID.randomUUID();
        tools.createEmailDraft(null, List.of("hr@corp.com"), "Subject", "<p>Hi</p>", null, "Java",
                applicationId.toString(), null, null, null, "key-1");

        ArgumentCaptor<CreateEmailDraftRequest> captor = ArgumentCaptor.forClass(CreateEmailDraftRequest.class);
        verify(draftService).createDraft(captor.capture());
        assertThat(captor.getValue().profileName()).isEqualTo("Java");
        assertThat(captor.getValue().applicationId()).isEqualTo(applicationId);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void rejectsMalformedIds() {
        assertThatThrownBy(() -> tools.createEmailDraft(null, List.of("hr@corp.com"), "S", "<p>x</p>",
                "not-a-uuid", null, null, null, null, null, null))
                .isInstanceOf(BadRequestException.class);
    }
}
