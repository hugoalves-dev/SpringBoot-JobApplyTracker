package com.jobtracker.unit.mcp;

import com.jobtracker.dto.analytics.AnalyticsResponse;
import com.jobtracker.dto.analytics.OrganizationSummary;
import com.jobtracker.dto.analytics.WeeklySummaryResponse;
import com.jobtracker.dto.application.ApplicationResponse;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.mapper.ApplicationMapper;
import com.jobtracker.mcp.tools.McpAnalyticsTools;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.service.ApplicationService;
import com.jobtracker.util.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAnalyticsToolsTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private ApplicationMapper applicationMapper;

    @Mock
    private ApplicationService applicationService;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private McpAnalyticsTools tools;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void getAnalytics_emptyList_returnsZeros() {
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID)).thenReturn(List.of());

        AnalyticsResponse result = tools.getAnalytics(null, null, null);

        assertThat(result.totalApplications()).isZero();
        assertThat(result.interviewCount()).isZero();
        assertThat(result.interviewRate()).isEqualTo(0.0);
        assertThat(result.rejectionCount()).isZero();
        assertThat(result.ghostingCount()).isZero();
        assertThat(result.averageDaysToResponse()).isEqualTo(0.0);
        assertThat(result.statusBreakdown()).isEmpty();
        assertThat(result.platformBreakdown()).isEmpty();
    }

    @Test
    void getAnalytics_countsCorrectly() {
        JobApplication rh = app("RH", false, LocalDate.now().minusDays(5), null);
        JobApplication interview = app("Pending HR Response", true, LocalDate.now().minusDays(3), "LinkedIn");
        interview.setInterviewCount(1);
        JobApplication rejected = app("Rejected", false, LocalDate.now().minusDays(10), "Gupy");
        JobApplication ghost = app("Ghosting", false, LocalDate.now().minusDays(20), null);
        JobApplication noStatus = app(null, false, null, null);

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(rh, interview, rejected, ghost, noStatus));

        AnalyticsResponse result = tools.getAnalytics(null, null, null);

        assertThat(result.totalApplications()).isEqualTo(5);
        assertThat(result.interviewCount()).isEqualTo(1);
        assertThat(result.interviewRate()).isEqualTo(20.0);
        assertThat(result.rejectionCount()).isEqualTo(1);
        assertThat(result.ghostingCount()).isEqualTo(1);
        assertThat(result.statusBreakdown()).containsKey("RH");
        assertThat(result.statusBreakdown()).containsKey("To Send Later");
        assertThat(result.platformBreakdown()).containsEntry("LinkedIn", 1);
        assertThat(result.platformBreakdown()).containsEntry("Gupy", 1);
        assertThat(result.platformBreakdown()).doesNotContainKey(null);
    }

    @Test
    void getAnalytics_sumsCompletedInterviewsAndKeepsApplicationRateBounded() {
        JobApplication first = app("Pending HR Response", false, LocalDate.now(), null);
        JobApplication second = app("Pending Hiring Manager Response", true, LocalDate.now(), null);
        JobApplication third = app("RH", false, LocalDate.now(), null);
        JobApplication scheduledOnly = app("RH", true, LocalDate.now(), null);
        first.setInterviewCount(2);
        second.setInterviewCount(2);
        third.setInterviewCount(1);
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(first, second, third, scheduledOnly));

        AnalyticsResponse result = tools.getAnalytics(null, null, null);

        assertThat(result.totalApplications()).isEqualTo(4);
        assertThat(result.interviewCount()).isEqualTo(5);
        assertThat(result.interviewRate()).isEqualTo(75.0);
    }

    @Test
    void getAnalytics_filtersDateRange() {
        LocalDate today = LocalDate.now();
        JobApplication inRange = app("RH", false, today.minusDays(3), null);
        JobApplication outOfRange = app("RH", false, today.minusDays(30), null);

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(inRange, outOfRange));

        AnalyticsResponse result = tools.getAnalytics(
                null,
                today.minusDays(7).toString(),
                today.toString());

        assertThat(result.totalApplications()).isEqualTo(1);
    }

    @Test
    void getAnalytics_averageDaysToResponse_onlyStatusNotNull() {
        LocalDate appDate = LocalDate.now().minusDays(10);
        JobApplication withStatus = app("RH", false, appDate, null);
        withStatus.setUpdatedAt(appDate.plusDays(5).atStartOfDay());

        JobApplication nullStatus = app(null, false, appDate, null);
        nullStatus.setUpdatedAt(appDate.plusDays(100).atStartOfDay());

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(withStatus, nullStatus));

        AnalyticsResponse result = tools.getAnalytics(null, null, null);

        assertThat(result.averageDaysToResponse()).isEqualTo(5.0);
    }

    @Test
    void getApplicationsByOrganization_groupsAndSorts() {
        JobApplication a1 = app("RH", false, LocalDate.now(), null);
        a1.setOrganization("Acme");
        JobApplication a2 = app("Pending HR Response", true, LocalDate.now(), null);
        a2.setOrganization("Acme");
        JobApplication a3 = app("RH", false, LocalDate.now(), null);
        a3.setOrganization("Beta");

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(a1, a2, a3));

        List<OrganizationSummary> result = tools.getApplicationsByOrganization(null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).organization()).isEqualTo("Acme");
        assertThat(result.get(0).totalApplications()).isEqualTo(2);
        assertThat(result.get(0).hasInterview()).isTrue();
        assertThat(result.get(1).organization()).isEqualTo("Beta");
        assertThat(result.get(1).totalApplications()).isEqualTo(1);
    }

    @Test
    void getApplicationsByOrganization_ignoresNullOrganization() {
        JobApplication noOrg = app("RH", false, LocalDate.now(), null);

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(noOrg));

        List<OrganizationSummary> result = tools.getApplicationsByOrganization(null);

        assertThat(result).isEmpty();
    }

    @Test
    void searchApplications_delegatesToRepositoryAndMaps() {
        UUID userId = USER_ID;
        JobApplication entity = app("RH", false, LocalDate.now(), null);
        ApplicationResponse response = applicationResponseWithId(UUID.randomUUID());

        when(securityUtils.getCurrentUserId()).thenReturn(userId);
        when(applicationRepository.searchApplications(eq(userId), eq("java"), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(applicationMapper.toResponse(entity)).thenReturn(response);

        List<ApplicationResponse> result = tools.searchApplications(null, "java");

        assertThat(result).containsExactly(response);
        verify(applicationRepository).searchApplications(eq(userId), eq("java"), any(Pageable.class));
    }

    @Test
    void getWeeklySummary_countsThisWeekAndLastWeek() {
        LocalDate today = LocalDate.now();
        JobApplication thisWeek1 = app("RH", false, today.minusDays(2), null);
        JobApplication thisWeek2 = app("RH", true, today.minusDays(1), null);
        thisWeek1.setOrganization("Acme");
        thisWeek2.setOrganization("Acme");
        JobApplication lastWeekApp = app("RH", false, today.minusDays(10), null);

        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID))
                .thenReturn(List.of(thisWeek1, thisWeek2, lastWeekApp));
        when(applicationService.getOverdue()).thenReturn(List.of());

        WeeklySummaryResponse result = tools.getWeeklySummary(null);

        assertThat(result.thisWeekApplications()).isEqualTo(2);
        assertThat(result.lastWeekApplications()).isEqualTo(1);
        assertThat(result.weekOverWeekDelta()).isEqualTo(1);
        assertThat(result.thisWeekInterviews()).isEqualTo(1);
        assertThat(result.overdueCount()).isZero();
        assertThat(result.topOrganizationsThisWeek()).containsExactly("Acme");
    }

    @Test
    void getWeeklySummary_overdueCountDelegatesToService() {
        when(securityUtils.getCurrentUserId()).thenReturn(USER_ID);
        when(applicationRepository.findAllByUser_IdAndArchivedFalse(USER_ID)).thenReturn(List.of());
        when(applicationService.getOverdue()).thenReturn(List.of(
                applicationResponseWithId(UUID.randomUUID()),
                applicationResponseWithId(UUID.randomUUID())));

        WeeklySummaryResponse result = tools.getWeeklySummary(null);

        assertThat(result.overdueCount()).isEqualTo(2);
    }

    private static JobApplication app(String status, boolean interviewScheduled,
                                       LocalDate applicationDate, String platform) {
        JobApplication a = new JobApplication();
        a.setStatus(status);
        a.setInterviewScheduled(interviewScheduled);
        a.setApplicationDate(applicationDate);
        a.setPlatform(platform);
        a.setUpdatedAt(LocalDateTime.now());
        return a;
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
