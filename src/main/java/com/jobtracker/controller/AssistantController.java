package com.jobtracker.controller;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.dto.assistant.AssistantChatRequest;
import com.jobtracker.dto.assistant.AssistantUsageResponse;
import com.jobtracker.service.assistant.AssistantProviderErrorMapper;
import com.jobtracker.service.assistant.AssistantService;
import com.jobtracker.service.assistant.AssistantService.AssistantStream;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Tag(name = "Assistant", description = "Read-only Ask ApplyWell assistant")
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantService assistant;
    private final AssistantProperties properties;
    private final AssistantProviderErrorMapper errorMapper;

    public AssistantController(AssistantService assistant, AssistantProperties properties,
                               AssistantProviderErrorMapper errorMapper) {
        this.assistant = assistant;
        this.properties = properties;
        this.errorMapper = errorMapper;
    }

    @PreAuthorize("hasRole('USER') or hasAuthority('SCOPE_read:applications')")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AssistantChatRequest request) {
        AssistantStream stream = assistant.stream(request.conversationId(), request.message());
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeout().toMillis());
        AtomicReference<Disposable> subscription = new AtomicReference<>();

        emitter.onCompletion(() -> dispose(subscription));
        emitter.onTimeout(() -> dispose(subscription));
        emitter.onError(error -> dispose(subscription));

        subscription.set(stream.content().subscribe(
                token -> send(emitter, "token", Map.of("content", token)),
                error -> {
                    send(emitter, "error", errorMapper.map(error));
                    emitter.complete();
                },
                () -> {
                    send(emitter, "complete", Map.of("sources", stream.sources().get()));
                    emitter.complete();
                }));
        return emitter;
    }

    @PreAuthorize("hasRole('USER') or hasAuthority('SCOPE_read:applications')")
    @GetMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    public AssistantUsageResponse usage() {
        return assistant.usage();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
    }

    private void dispose(AtomicReference<Disposable> reference) {
        Disposable disposable = reference.get();
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
    }
}
