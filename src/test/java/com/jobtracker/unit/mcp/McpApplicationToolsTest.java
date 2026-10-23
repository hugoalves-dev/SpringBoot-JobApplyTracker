package com.jobtracker.unit.mcp;

import com.jobtracker.dto.application.ApplicationFilter;
import com.jobtracker.dto.application.ApplicationPageResponse;
import com.jobtracker.dto.application.ApplicationPatchRequest;
import com.jobtracker.dto.application.ApplicationRequest;
import com.jobtracker.dto.application.ApplicationResponse;
import com.jobtracker.dto.application.MarkDmSentRequest;
import com.jobtracker.dto.application.UpdateReminderRequest;
import com.jobtracker.dto.application.UpdateStatusRequest;
import com.jobtracker.mcp.tools.McpApplicationTools;
import com.jobtracker.service.ApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the application MCP tools. Auditing is applied by {@code McpAuditAspect} at the
 * proxy layer and is not exercised here, so the tools are tested as plain delegations to
 * {@link ApplicationService}. The framework-injected {@code McpSyncRequestContext} is passed as
 * {@code null}.
 */
@ExtendWith(MockitoExtension.class)
class McpApplicationToolsTest {

    @Mock
    private ApplicationService applicationService;

    @InjectMocks
    private McpApplicationTools tools;

    @Test
    void listApplications_allNullParams_usesDefaults() {
        ApplicationPageResponse expected = new ApplicationPageResponse(List.of(), 0, 20, 0, 0);
        when(applicationService.getAll(any(ApplicationFilter.class), eq(0), eq(20), eq("createdAt,desc")))
                .thenReturn(expected);

        ApplicationPageResponse result = tools.listApplications(null, null, null, null, null, null, null, null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(applicationService).getAll(any(ApplicationFilter.class), eq(0), eq(20), eq("createdAt,desc"));
    }

    @Test
    void listApplications_parsesDateStrings() {
        ApplicationPageResponse expected = new ApplicationPageResponse(List.of(), 0, 20, 0, 0);
        when(applicationService.getAll(any(ApplicationFilter.class), eq(0), eq(20), any()))
                .thenReturn(expected);

        tools.listApplications(null, null, null, "2025-01-01", "2025-06-30", null, null, null, null, null);

        ArgumentCaptor<ApplicationFilter> captor = ArgumentCaptor.forClass(ApplicationFilter.class);
        verify(applicationService).getAll(captor.capture(), eq(0), eq(20), eq("createdAt,desc"));
        assertThat(captor.getValue().applicationDateFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(captor.getValue().applicationDateTo()).isEqualTo(LocalDate.of(2025, 6, 30));
    }

    @Test
    void listApplications_honorsExplicitPageAndSort() {
        ApplicationPageResponse expected = new ApplicationPageResponse(List.of(), 2, 5, 0, 0);
        when(applicationService.getAll(any(ApplicationFilter.class), eq(2), eq(5), eq("applicationDate,asc")))
                .thenReturn(expected);

        tools.listApplications(null, null, null, null, null, null, null, 2, 5, "applicationDate,asc");

        verify(applicationService).getAll(any(ApplicationFilter.class), eq(2), eq(5), eq("applicationDate,asc"));
    }

    @Test
    void getApplication_parsesUuid() {
        UUID id = UUID.randomUUID();
        ApplicationResponse expected = applicationResponseWithId(id);
        when(applicationService.getById(id)).thenReturn(expected);

        ApplicationResponse result = tools.getApplication(null, id.toString());

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getUpcomingApplications_delegatesToService() {
        ApplicationResponse resp = applicationResponseWithId(UUID.randomUUID());
        when(applicationService.getUpcoming()).thenReturn(List.of(resp));

        List<ApplicationResponse> result = tools.getUpcomingApplications(null);

        assertThat(result).containsExactly(resp);
    }

    @Test
    void getOverdueApplications_delegatesToService() {
        when(applicationService.getOverdue()).thenReturn(List.of());

        List<ApplicationResponse> result = tools.getOverdueApplications(null);

        assertThat(result).isEmpty();
    }

    @Test
    void createApplicationTool_descriptionMandatesAutomaticRegistration() {
        String description = toolDescription("createApplication");

        assertThat(description)
                .contains("must be called automatically")
                .contains("applying")
                .contains("generating a resume")
                .contains("contacting a recruiter")
                .contains("evaluating job fit")
                .contains("preparing application materials")
                .containsIgnoringCase("must search before creating")
                .containsIgnoringCase("active and archived")
                .containsIgnoringCase("possible duplicate")
                .containsIgnoringCase("an empty search result is not sufficient")
                .containsIgnoringCase("do not call Create-Application until");
    }

    @Test
    void createApplication_mapsAllParams() {
        ArgumentCaptor<ApplicationRequest> captor = ArgumentCaptor.forClass(ApplicationRequest.class);
        ApplicationResponse expected = applicationResponseWithId(UUID.randomUUID());
        when(applicationService.create(any())).thenReturn(expected);

        tools.createApplication(
                null,
                "Backend Engineer",
                "Jane Smith",
                "TechCorp",
                "https://example.com/job",
                "2025-06-01",
                Boolean.TRUE,
                Boolean.FALSE,
                "2025-06-10T14:00:00",
                "RH",
                Boolean.TRUE,
                "Follow up Monday",
                "LinkedIn");

        verify(applicationService).create(captor.capture());
        ApplicationRequest req = captor.getValue();
        assertThat(req.vacancyName()).isEqualTo("Backend Engineer");
        assertThat(req.recruiterName()).isEqualTo("Jane Smith");
        assertThat(req.organization()).isEqualTo("TechCorp");
        assertThat(req.applicationDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(req.rhAcceptedConnection()).isTrue();
        assertThat(req.interviewScheduled()).isFalse();
        assertThat(req.nextStepDateTime()).isEqualTo(LocalDateTime.of(2025, 6, 10, 14, 0, 0));
        assertThat(req.status()).isEqualTo("RH");
        assertThat(req.recruiterDmReminderEnabled()).isTrue();
        assertThat(req.note()).isEqualTo("Follow up Monday");
        assertThat(req.platform()).isEqualTo("LinkedIn");
    }

    @Test
    void createApplication_nullBooleans_defaultToFalse() {
        ArgumentCaptor<ApplicationRequest> captor = ArgumentCaptor.forClass(ApplicationRequest.class);
        when(applicationService.create(any())).thenReturn(applicationResponseWithId(UUID.randomUUID()));

        tools.createApplication(null, "Vacancy", null, null, null, null, null, null, null, null, null, null, null);

        verify(applicationService).create(captor.capture());
        ApplicationRequest req = captor.getValue();
        assertThat(req.rhAcceptedConnection()).isFalse();
        assertThat(req.interviewScheduled()).isFalse();
        assertThat(req.recruiterDmReminderEnabled()).isFalse();
    }

    @Test
    void patchApplication_sendsOnlyTheProvidedFields() {
        UUID id = UUID.randomUUID();
        ArgumentCaptor<ApplicationPatchRequest> captor = ArgumentCaptor.forClass(ApplicationPatchRequest.class);
        when(applicationService.patch(eq(id), any())).thenReturn(applicationResponseWithId(id));

        tools.patchApplication(null, id.toString(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, Boolean.FALSE);

        verify(applicationService).patch(eq(id), captor.capture());
        ApplicationPatchRequest request = captor.getValue();
        assertThat(request.hasArchived()).isTrue();
        assertThat(request.getArchived()).isFalse();
        assertThat(request.hasInterviewCount()).isFalse();
        assertThat(request.hasStatus()).isFalse();
        assertThat(request.hasVacancyName()).isFalse();
        assertThat(request.hasRhAcceptedConnection()).isFalse();
        assertThat(request.hasInterviewScheduled()).isFalse();
        assertThat(request.hasRecruiterDmReminderEnabled()).isFalse();
    }

    @Test
    void patchApplication_forwardsExplicitZeroInterviewCount() {
        UUID id = UUID.randomUUID();
        ArgumentCaptor<ApplicationPatchRequest> captor = ArgumentCaptor.forClass(ApplicationPatchRequest.class);
        tools.patchApplication(null, id.toString(), null, null, null, null,
                null, null, null, null, null, null, null, null, 0, null);
        verify(applicationService).patch(eq(id), captor.capture());
        assertThat(captor.getValue().hasInterviewCount()).isTrue();
        assertThat(captor.getValue().getInterviewCount()).isZero();
        assertThat(captor.getValue().hasStatus()).isFalse();
    }

    @Test
    void patchApplication_rejectsNegativeInterviewCount() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                tools.patchApplication(null, UUID.randomUUID().toString(), null, null, null, null,
                        null, null, null, null, null, null, null, null, -1, null))
                .isInstanceOf(com.jobtracker.exception.BadRequestException.class)
                .hasMessageContaining("interviewCount");
        org.mockito.Mockito.verifyNoInteractions(applicationService);
    }

    @Test
    void patchApplication_mapsAllParams() {
        UUID id = UUID.randomUUID();
        ArgumentCaptor<ApplicationPatchRequest> captor = ArgumentCaptor.forClass(ApplicationPatchRequest.class);
        when(applicationService.patch(eq(id), any())).thenReturn(applicationResponseWithId(id));

        tools.patchApplication(
                null,
                id.toString(),
                "Backend Engineer",
                "Jane Smith",
                "TechCorp",
                "https://example.com/job",
                "2025-06-01",
                Boolean.TRUE,
                Boolean.FALSE,
                "2025-06-10T14:00:00",
                "RH",
                Boolean.TRUE,
                "Follow up Monday",
                "LinkedIn",
                2, Boolean.TRUE);

        verify(applicationService).patch(eq(id), captor.capture());
        ApplicationPatchRequest request = captor.getValue();
        assertThat(request.getVacancyName()).isEqualTo("Backend Engineer");
        assertThat(request.getRecruiterName()).isEqualTo("Jane Smith");
        assertThat(request.getOrganization()).isEqualTo("TechCorp");
        assertThat(request.getVacancyLink()).isEqualTo("https://example.com/job");
        assertThat(request.getApplicationDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(request.getRhAcceptedConnection()).isTrue();
        assertThat(request.getInterviewScheduled()).isFalse();
        assertThat(request.getNextStepDateTime()).isEqualTo(LocalDateTime.of(2025, 6, 10, 14, 0, 0));
        assertThat(request.getStatus()).isEqualTo("RH");
        assertThat(request.getRecruiterDmReminderEnabled()).isTrue();
        assertThat(request.getNote()).isEqualTo("Follow up Monday");
        assertThat(request.getPlatform()).isEqualTo("LinkedIn");
        assertThat(request.getArchived()).isTrue();
        assertThat(request.getInterviewCount()).isEqualTo(2);
    }

    @Test
    void patchApplication_returnsUpdatedApplication() {
        UUID id = UUID.randomUUID();
        ApplicationResponse expected = applicationResponseWithId(id);
        when(applicationService.patch(eq(id), any())).thenReturn(expected);

        ApplicationResponse result = tools.patchApplication(null, id.toString(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, Boolean.FALSE);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void patchApplicationTool_isExposedWithPartialUpdateAndArchiveSemantics() {
        Method method = toolMethod("patchApplication");

        assertThat(method.getAnnotation(McpTool.class).name()).isEqualTo("Patch-Application");
        assertThat(method.getAnnotation(McpTool.class).description())
                .containsIgnoringCase("partially update")
                .contains("omitted field")
                .contains("archived")
                .containsIgnoringCase("restore")
                .contains("List-Statuses")
                .contains("never archives or restores");
    }

    @Test
    void patchApplicationTool_schemaMarksOnlyIdAsRequired() {
        Method method = toolMethod("patchApplication");
        Parameter[] parameters = method.getParameters();

        // parameters[0] is the framework-injected McpSyncRequestContext, which carries no @McpToolParam.
        assertThat(parameters[1].getAnnotation(McpToolParam.class).required()).isTrue();
        assertThat(java.util.Arrays.stream(parameters)
                .map(p -> p.getAnnotation(McpToolParam.class))
                .filter(java.util.Objects::nonNull)
                .filter(McpToolParam::required)
                .count()).isEqualTo(1);

        Parameter archived = parameters[parameters.length - 1];
        assertThat(archived.getType()).isEqualTo(Boolean.class);
        assertThat(archived.getAnnotation(McpToolParam.class).required()).isFalse();
        assertThat(archived.getAnnotation(McpToolParam.class).description())
                .contains("archive")
                .contains("restore");
    }

    @Test
    void updateApplicationStatus_buildsCorrectRequest() {
        UUID id = UUID.randomUUID();
        ApplicationResponse expected = applicationResponseWithId(id);
        ArgumentCaptor<UpdateStatusRequest> captor = ArgumentCaptor.forClass(UpdateStatusRequest.class);
        when(applicationService.updateStatus(eq(id), any())).thenReturn(expected);

        tools.updateApplicationStatus(null, id.toString(), "Teste Técnico");

        verify(applicationService).updateStatus(eq(id), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("Teste Técnico");
    }

    @Test
    void updateApplicationStatusTool_descriptionSeparatesStatusFromArchival() {
        assertThat(toolDescription("updateApplicationStatus"))
                .contains("does not archive")
                .contains("Rejected")
                .contains("status only");
    }

    @Test
    void updateApplicationReminder_passesEnabledFlag() {
        UUID id = UUID.randomUUID();
        ApplicationResponse expected = applicationResponseWithId(id);
        ArgumentCaptor<UpdateReminderRequest> captor = ArgumentCaptor.forClass(UpdateReminderRequest.class);
        when(applicationService.updateReminder(eq(id), any())).thenReturn(expected);

        tools.updateApplicationReminder(null, id.toString(), true);

        verify(applicationService).updateReminder(eq(id), captor.capture());
        assertThat(captor.getValue().recruiterDmReminderEnabled()).isTrue();
    }

    @Test
    void markRecruiterDmSent_delegatesWithEmptyRequest() {
        UUID id = UUID.randomUUID();
        when(applicationService.markDmSent(eq(id), any(MarkDmSentRequest.class)))
                .thenReturn(applicationResponseWithId(id));

        tools.markRecruiterDmSent(null, id.toString());

        verify(applicationService).markDmSent(eq(id), any(MarkDmSentRequest.class));
    }

    @Test
    void archiveApplication_delegatesToService() {
        UUID id = UUID.randomUUID();
        when(applicationService.archive(id)).thenReturn(applicationResponseWithId(id));

        tools.archiveApplication(null, id.toString());

        verify(applicationService).archive(id);
    }

    @Test
    void archiveApplicationTool_descriptionRequiresExplicitArchiveIntent() {
        assertThat(toolDescription("archiveApplication"))
                .containsIgnoringCase("soft-delete")
                .contains("Never infer")
                .contains("Rejected")
                .contains("Approved")
                .contains("explicit");
    }

    @Test
    void restoreApplication_delegatesToService() {
        UUID id = UUID.randomUUID();
        when(applicationService.restore(id)).thenReturn(applicationResponseWithId(id));

        tools.restoreApplication(null, id.toString());

        verify(applicationService).restore(id);
    }

    @Test
    void deleteApplication_delegatesToService() {
        UUID id = UUID.randomUUID();

        tools.deleteApplication(null, id.toString());

        verify(applicationService).delete(id);
    }

    @Test
    void deleteApplicationTool_descriptionRequiresExplicitPermanentDeletion() {
        assertThat(toolDescription("deleteApplication"))
                .containsIgnoringCase("permanently")
                .contains("explicitly requests permanent deletion");
    }

    private static String toolDescription(String javaMethodName) {
        return toolMethod(javaMethodName).getAnnotation(McpTool.class).description();
    }

    private static Method toolMethod(String javaMethodName) {
        return java.util.Arrays.stream(McpApplicationTools.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(javaMethodName))
                .findFirst()
                .orElseThrow();
    }

    private static ApplicationResponse applicationResponseWithId(UUID id) {
        return new ApplicationResponse(
                id, null, null, null, null,
                null,
                false, false,
                null, null, null,
                false, null, null,
                null,
                false, null,
                null, null, null, null, null,
                false, 0, null, null);
    }
}
