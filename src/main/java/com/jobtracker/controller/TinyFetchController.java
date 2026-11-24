package com.jobtracker.controller;

import com.jobtracker.dto.integrations.FetchUrlRequest;
import com.jobtracker.dto.integrations.FetchUrlResponse;
import com.jobtracker.dto.integrations.IntegrationTestResponse;
import com.jobtracker.dto.integrations.TinyFetchSettingsRequest;
import com.jobtracker.dto.integrations.TinyFetchSettingsResponse;
import com.jobtracker.service.integrations.tinyfetch.TinyFetchService;
import com.jobtracker.config.UserSessionOnly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Integrations - TinyFish Fetch", description = "Per-user TinyFish Fetch configuration and URL reading")
@RestController
@RequestMapping("/api/v1/integrations/tinyfetch")
public class TinyFetchController {

    private final TinyFetchService tinyFetchService;

    public TinyFetchController(TinyFetchService tinyFetchService) {
        this.tinyFetchService = tinyFetchService;
    }

    @Operation(summary = "Get TinyFish Fetch settings (the API key is never returned)")
    @GetMapping
    public ResponseEntity<TinyFetchSettingsResponse> getSettings() {
        return ResponseEntity.ok(tinyFetchService.getSettings());
    }

    @Operation(summary = "Create or update TinyFish Fetch settings")
    @UserSessionOnly
    @PutMapping
    public ResponseEntity<TinyFetchSettingsResponse> updateSettings(@Valid @RequestBody TinyFetchSettingsRequest request) {
        return ResponseEntity.ok(tinyFetchService.updateSettings(request));
    }

    @Operation(summary = "Remove TinyFish Fetch settings and the stored API key")
    @UserSessionOnly
    @DeleteMapping
    public ResponseEntity<Void> deleteSettings() {
        tinyFetchService.deleteSettings();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Test the stored API key with a single fetch (counts toward the quota)")
    @PostMapping("/test")
    public ResponseEntity<IntegrationTestResponse> test() {
        return ResponseEntity.ok(tinyFetchService.testConnection());
    }

    @Operation(summary = "Read a public URL and return its content as Markdown")
    @PostMapping("/fetch")
    public ResponseEntity<FetchUrlResponse> fetch(@Valid @RequestBody FetchUrlRequest request) {
        return ResponseEntity.ok(tinyFetchService.fetch(request.url(), Boolean.TRUE.equals(request.bypassCache())));
    }
}
