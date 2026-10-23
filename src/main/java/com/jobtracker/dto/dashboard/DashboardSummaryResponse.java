package com.jobtracker.dto.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Aggregate dashboard statistics for the authenticated user")
public record DashboardSummaryResponse(
        @Schema(description = "Total number of job applications", example = "25")
        long totalApplications,
        @Schema(description = "Applications still awaiting a response", example = "10")
        long waitingResponses,
        @Schema(description = "Applications with an interview scheduled", example = "3")
        long interviewsScheduled,
        @Schema(description = "Total confirmed completed interviews across active applications", example = "7")
        long interviewCount,
        @Schema(description = "Applications with overdue follow-up dates", example = "2")
        long overdueFollowUps,
        @Schema(description = "Applications with recruiter DM reminder enabled", example = "5")
        long dmRemindersEnabled,
        @Schema(description = "Applications marked to send later (status is null)", example = "4")
        long toSendLater,
        @Schema(description = "Applications marked as Rejeitado", example = "2")
        long rejectedCount,
        @Schema(description = "Applications marked as Ghosting", example = "3")
        long ghostingCount,
        @Schema(description = "Average applications per day over the last 30 days", example = "1.2")
        double averageDailyApplications,
        @Schema(description = "Average applications per week over the last 12 weeks", example = "6.5")
        double averageWeeklyApplications,
        @Schema(description = "Average applications per month over the last 12 months", example = "18.0")
        double averageMonthlyApplications
) {}
