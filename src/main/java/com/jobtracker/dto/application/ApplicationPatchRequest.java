package com.jobtracker.dto.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Partial update payload for a job application.
 *
 * <p>Every field is optional. Only the JSON properties actually present in the request body are
 * applied; omitted properties leave the stored value untouched. Presence is tracked by the setters,
 * so an explicit {@code null} is distinguishable from an omitted property and clears the field.
 *
 * <p>Fields backed by a non-nullable column ({@code rhAcceptedConnection}, {@code interviewScheduled},
 * {@code recruiterDmReminderEnabled}, {@code interviewCount} and {@code archived}) cannot be cleared:
 * an explicit {@code null} for those is ignored and behaves like an omitted property.
 */
@Schema(description = "Partial update payload for a job application. Omitted fields keep their current value.")
public class ApplicationPatchRequest {

    private final Set<String> providedFields = new HashSet<>();

    @Schema(description = "Job title or vacancy name", example = "Backend Engineer")
    private String vacancyName;

    @Schema(description = "Name of the recruiter", example = "Jane Smith")
    private String recruiterName;

    @Schema(description = "Organization or company that posted the vacancy", example = "TechCorp")
    private String organization;

    @Schema(description = "URL link to the vacancy posting", example = "https://example.com/jobs/123")
    @Pattern(regexp = "^(https?|ftp)://.*", message = "Vacancy link must be a valid URL")
    private String vacancyLink;

    @Schema(description = "Date the application was submitted (yyyy-MM-dd)", example = "2024-06-01")
    @PastOrPresent(message = "Application date cannot be in the future")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate applicationDate;

    @Schema(description = "Whether the recruiter accepted a LinkedIn connection", example = "true")
    private Boolean rhAcceptedConnection;

    @Schema(description = "Whether an interview has been scheduled", example = "false")
    private Boolean interviewScheduled;

    @Schema(description = "Date/time of the next step (yyyy-MM-dd'T'HH:mm:ss)", example = "2024-06-10T14:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextStepDateTime;

    @Schema(description = "Application status display name. Send null to move the record back to 'To Send Later'.",
            example = "Rejected")
    private String status;

    @Schema(description = "Whether a DM reminder to the recruiter is enabled", example = "true")
    private Boolean recruiterDmReminderEnabled;

    @Schema(description = "Personal notes about this application", example = "Follow up next Monday")
    private String note;

    @Schema(description = "Platform or job board where the vacancy was found", example = "LinkedIn")
    private String platform;

    @Schema(description = "Number of interviews held for this application", example = "2")
    @Min(0)
    private Integer interviewCount;

    @Schema(description = "Whether the application is archived. false restores it to the active list.",
            example = "false")
    private Boolean archived;

    public String getVacancyName() { return vacancyName; }

    @JsonProperty("vacancyName")
    public void setVacancyName(String vacancyName) {
        this.vacancyName = vacancyName;
        providedFields.add("vacancyName");
    }

    public String getRecruiterName() { return recruiterName; }

    @JsonProperty("recruiterName")
    public void setRecruiterName(String recruiterName) {
        this.recruiterName = recruiterName;
        providedFields.add("recruiterName");
    }

    public String getOrganization() { return organization; }

    @JsonProperty("organization")
    public void setOrganization(String organization) {
        this.organization = organization;
        providedFields.add("organization");
    }

    public String getVacancyLink() { return vacancyLink; }

    @JsonProperty("vacancyLink")
    public void setVacancyLink(String vacancyLink) {
        this.vacancyLink = vacancyLink;
        providedFields.add("vacancyLink");
    }

    public LocalDate getApplicationDate() { return applicationDate; }

    @JsonProperty("applicationDate")
    public void setApplicationDate(LocalDate applicationDate) {
        this.applicationDate = applicationDate;
        providedFields.add("applicationDate");
    }

    public Boolean getRhAcceptedConnection() { return rhAcceptedConnection; }

    @JsonProperty("rhAcceptedConnection")
    public void setRhAcceptedConnection(Boolean rhAcceptedConnection) {
        if (rhAcceptedConnection == null) {
            return;
        }
        this.rhAcceptedConnection = rhAcceptedConnection;
        providedFields.add("rhAcceptedConnection");
    }

    public Boolean getInterviewScheduled() { return interviewScheduled; }

    @JsonProperty("interviewScheduled")
    public void setInterviewScheduled(Boolean interviewScheduled) {
        if (interviewScheduled == null) {
            return;
        }
        this.interviewScheduled = interviewScheduled;
        providedFields.add("interviewScheduled");
    }

    public LocalDateTime getNextStepDateTime() { return nextStepDateTime; }

    @JsonProperty("nextStepDateTime")
    public void setNextStepDateTime(LocalDateTime nextStepDateTime) {
        this.nextStepDateTime = nextStepDateTime;
        providedFields.add("nextStepDateTime");
    }

    public String getStatus() { return status; }

    @JsonProperty("status")
    public void setStatus(String status) {
        this.status = status;
        providedFields.add("status");
    }

    public Boolean getRecruiterDmReminderEnabled() { return recruiterDmReminderEnabled; }

    @JsonProperty("recruiterDmReminderEnabled")
    public void setRecruiterDmReminderEnabled(Boolean recruiterDmReminderEnabled) {
        if (recruiterDmReminderEnabled == null) {
            return;
        }
        this.recruiterDmReminderEnabled = recruiterDmReminderEnabled;
        providedFields.add("recruiterDmReminderEnabled");
    }

    public String getNote() { return note; }

    @JsonProperty("note")
    public void setNote(String note) {
        this.note = note;
        providedFields.add("note");
    }

    public String getPlatform() { return platform; }

    @JsonProperty("platform")
    public void setPlatform(String platform) {
        this.platform = platform;
        providedFields.add("platform");
    }

    public Integer getInterviewCount() { return interviewCount; }

    @JsonProperty("interviewCount")
    public void setInterviewCount(Integer interviewCount) {
        if (interviewCount == null) {
            return;
        }
        if (interviewCount < 0) {
            throw new com.jobtracker.exception.BadRequestException("interviewCount must be zero or greater");
        }
        this.interviewCount = interviewCount;
        providedFields.add("interviewCount");
    }

    public Boolean getArchived() { return archived; }

    @JsonProperty("archived")
    public void setArchived(Boolean archived) {
        if (archived == null) {
            return;
        }
        this.archived = archived;
        providedFields.add("archived");
    }

    public boolean hasVacancyName() { return providedFields.contains("vacancyName"); }

    public boolean hasRecruiterName() { return providedFields.contains("recruiterName"); }

    public boolean hasOrganization() { return providedFields.contains("organization"); }

    public boolean hasVacancyLink() { return providedFields.contains("vacancyLink"); }

    public boolean hasApplicationDate() { return providedFields.contains("applicationDate"); }

    public boolean hasRhAcceptedConnection() { return providedFields.contains("rhAcceptedConnection"); }

    public boolean hasInterviewScheduled() { return providedFields.contains("interviewScheduled"); }

    public boolean hasNextStepDateTime() { return providedFields.contains("nextStepDateTime"); }

    public boolean hasStatus() { return providedFields.contains("status"); }

    public boolean hasRecruiterDmReminderEnabled() { return providedFields.contains("recruiterDmReminderEnabled"); }

    public boolean hasNote() { return providedFields.contains("note"); }

    public boolean hasPlatform() { return providedFields.contains("platform"); }

    public boolean hasInterviewCount() { return providedFields.contains("interviewCount"); }

    public boolean hasArchived() { return providedFields.contains("archived"); }
}
