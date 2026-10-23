package com.jobtracker.mcp.tools;

import com.jobtracker.dto.analytics.AnalyticsResponse;
import com.jobtracker.dto.analytics.OrganizationSummary;
import com.jobtracker.dto.analytics.WeeklySummaryResponse;
import com.jobtracker.dto.application.ApplicationResponse;
import com.jobtracker.entity.JobApplication;
import com.jobtracker.mapper.ApplicationMapper;
import com.jobtracker.mcp.audit.AuditMcpOperation;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.service.ApplicationService;
import com.jobtracker.util.SecurityUtils;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class McpAnalyticsTools {

    private final ApplicationRepository applicationRepository;
    private final ApplicationMapper applicationMapper;
    private final ApplicationService applicationService;
    private final SecurityUtils securityUtils;

    public McpAnalyticsTools(ApplicationRepository applicationRepository,
                              ApplicationMapper applicationMapper,
                              ApplicationService applicationService,
                              SecurityUtils securityUtils) {
        this.applicationRepository = applicationRepository;
        this.applicationMapper = applicationMapper;
        this.applicationService = applicationService;
        this.securityUtils = securityUtils;
    }

    @McpTool(
            name = "Get-Analytics",
            title = "Get Analytics",
            description = "Returns aggregate analytics for the user's job applications: totals, rates, status and platform breakdown, average days to response. "
                    + "interviewCount is the sum of completed interviews; interviewRate is the percentage of applications with at least one completed interview.",
            annotations = @McpAnnotations(
                    title = "Get Analytics",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @AuditMcpOperation(action = "Get-Analytics")
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(
            McpSyncRequestContext ctx,
            @McpToolParam(required = false, description = "Filter start date yyyy-MM-dd (inclusive), based on applicationDate") String dateFrom,
            @McpToolParam(required = false, description = "Filter end date yyyy-MM-dd (inclusive), based on applicationDate") String dateTo) {
        UUID userId = securityUtils.getCurrentUserId();
        LocalDate from = dateFrom != null ? LocalDate.parse(dateFrom) : null;
        LocalDate to = dateTo != null ? LocalDate.parse(dateTo) : null;

        List<JobApplication> apps = applicationRepository.findAllByUser_IdAndArchivedFalse(userId);

        if (from != null) {
            apps = apps.stream()
                    .filter(a -> a.getApplicationDate() != null && !a.getApplicationDate().isBefore(from))
                    .toList();
        }
        if (to != null) {
            apps = apps.stream()
                    .filter(a -> a.getApplicationDate() != null && !a.getApplicationDate().isAfter(to))
                    .toList();
        }

        int total = apps.size();
        int interviewCount = apps.stream().mapToInt(JobApplication::getInterviewCount).sum();
        long applicationsWithInterviews = apps.stream().filter(a -> a.getInterviewCount() > 0).count();
        int rejectionCount = (int) apps.stream().filter(a ->
                "REJEITADO".equals(a.getStatus()) || "Rejected".equals(a.getStatus())).count();
        int ghostingCount = (int) apps.stream().filter(a ->
                "GHOSTING".equals(a.getStatus()) || "Ghosting".equals(a.getStatus())).count();

        double interviewRate = total > 0 ? Math.round(applicationsWithInterviews * 1000.0 / total) / 10.0 : 0.0;
        double rejectionRate = total > 0 ? Math.round(rejectionCount * 1000.0 / total) / 10.0 : 0.0;
        double ghostingRate = total > 0 ? Math.round(ghostingCount * 1000.0 / total) / 10.0 : 0.0;

        double rawAvg = apps.stream()
                .filter(a -> a.getStatus() != null
                        && a.getApplicationDate() != null
                        && a.getUpdatedAt() != null)
                .mapToLong(a -> ChronoUnit.DAYS.between(a.getApplicationDate(), a.getUpdatedAt().toLocalDate()))
                .average()
                .orElse(0.0);
        double averageDaysToResponse = Math.round(rawAvg * 10.0) / 10.0;

        Map<String, Integer> statusBreakdown = new LinkedHashMap<>();
        for (JobApplication app : apps) {
            String key = app.getStatus() != null ? app.getStatus() : "To Send Later";
            statusBreakdown.merge(key, 1, Integer::sum);
        }

        Map<String, Integer> platformBreakdown = new LinkedHashMap<>();
        apps.stream()
                .filter(a -> a.getPlatform() != null && !a.getPlatform().isBlank())
                .forEach(a -> platformBreakdown.merge(a.getPlatform(), 1, Integer::sum));

        return new AnalyticsResponse(
                total, interviewCount, interviewRate,
                rejectionCount, rejectionRate,
                ghostingCount, ghostingRate,
                averageDaysToResponse,
                statusBreakdown, platformBreakdown);
    }

    @McpTool(
            name = "Get-Applications-By-Organization",
            title = "Get Applications By Organization",
            description = "Groups non-archived applications by company and returns a summary per organization, sorted by application count descending.",
            annotations = @McpAnnotations(
                    title = "Get Applications By Organization",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @AuditMcpOperation(action = "Get-Applications-By-Organization")
    @Transactional(readOnly = true)
    public List<OrganizationSummary> getApplicationsByOrganization(McpSyncRequestContext ctx) {
        UUID userId = securityUtils.getCurrentUserId();
        List<JobApplication> apps = applicationRepository.findAllByUser_IdAndArchivedFalse(userId);

        Map<String, List<JobApplication>> byOrg = apps.stream()
                .filter(a -> a.getOrganization() != null && !a.getOrganization().isBlank())
                .collect(Collectors.groupingBy(JobApplication::getOrganization));

        return byOrg.entrySet().stream()
                .map(entry -> {
                    List<JobApplication> orgApps = entry.getValue();
                    List<String> statuses = orgApps.stream()
                            .map(JobApplication::getStatus)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList();
                    boolean hasInterview = orgApps.stream().anyMatch(JobApplication::isInterviewScheduled);
                    LocalDate lastDate = orgApps.stream()
                            .map(JobApplication::getApplicationDate)
                            .filter(Objects::nonNull)
                            .max(LocalDate::compareTo)
                            .orElse(null);
                    return new OrganizationSummary(entry.getKey(), orgApps.size(), statuses, hasInterview, lastDate);
                })
                .sorted(Comparator.comparingInt(OrganizationSummary::totalApplications).reversed())
                .toList();
    }

    @McpTool(
            name = "Search-Applications",
            title = "Search Applications",
            description = "Full-text search across vacancyName, organization, recruiterName and note fields. Returns up to 20 matching non-archived applications.",
            annotations = @McpAnnotations(
                    title = "Search Applications",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @AuditMcpOperation(action = "Search-Applications")
    @Transactional(readOnly = true)
    public List<ApplicationResponse> searchApplications(
            McpSyncRequestContext ctx,
            @McpToolParam(required = true, description = "Search term — case-insensitive, matched against vacancyName, organization, recruiterName, note") String query) {
        UUID userId = securityUtils.getCurrentUserId();
        return applicationRepository.searchApplications(userId, query, Pageable.ofSize(20))
                .stream()
                .map(applicationMapper::toResponse)
                .toList();
    }

    @McpTool(
            name = "Get-Weekly-Summary",
            title = "Get Weekly Summary",
            description = "Returns application counts for the current week (last 7 days) and the previous week (days 8–14), plus overdue follow-up count and top organizations.",
            annotations = @McpAnnotations(
                    title = "Get Weekly Summary",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @AuditMcpOperation(action = "Get-Weekly-Summary")
    @Transactional(readOnly = true)
    public WeeklySummaryResponse getWeeklySummary(McpSyncRequestContext ctx) {
        UUID userId = securityUtils.getCurrentUserId();
        LocalDate today = LocalDate.now();
        LocalDate thisWeekStart = today.minusDays(6);
        LocalDate lastWeekStart = today.minusDays(13);
        LocalDate lastWeekEnd = today.minusDays(7);

        List<JobApplication> allApps = applicationRepository.findAllByUser_IdAndArchivedFalse(userId);

        List<JobApplication> thisWeek = allApps.stream()
                .filter(a -> a.getApplicationDate() != null
                        && !a.getApplicationDate().isBefore(thisWeekStart)
                        && !a.getApplicationDate().isAfter(today))
                .toList();

        List<JobApplication> lastWeek = allApps.stream()
                .filter(a -> a.getApplicationDate() != null
                        && !a.getApplicationDate().isBefore(lastWeekStart)
                        && !a.getApplicationDate().isAfter(lastWeekEnd))
                .toList();

        int thisWeekInterviews = (int) thisWeek.stream().filter(JobApplication::isInterviewScheduled).count();
        int overdueCount = applicationService.getOverdue().size();

        List<String> topOrgs = thisWeek.stream()
                .filter(a -> a.getOrganization() != null && !a.getOrganization().isBlank())
                .collect(Collectors.groupingBy(JobApplication::getOrganization, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        return new WeeklySummaryResponse(
                thisWeek.size(),
                lastWeek.size(),
                thisWeek.size() - lastWeek.size(),
                thisWeekInterviews,
                overdueCount,
                topOrgs);
    }
}
