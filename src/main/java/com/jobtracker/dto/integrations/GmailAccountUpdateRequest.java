package com.jobtracker.dto.integrations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GmailAccountUpdateRequest(@NotBlank @Size(max = 60) String profileName) {}
