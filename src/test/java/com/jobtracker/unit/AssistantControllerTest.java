package com.jobtracker.unit;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.controller.AssistantController;
import com.jobtracker.dto.assistant.AssistantErrorPayload;
import com.jobtracker.dto.assistant.AssistantUsageResponse;
import com.jobtracker.exception.AssistantQuotaExceededException;
import com.jobtracker.exception.GlobalExceptionHandler;
import com.jobtracker.service.assistant.AssistantProviderErrorMapper;
import com.jobtracker.service.assistant.AssistantService;
import com.jobtracker.service.assistant.AssistantService.AssistantStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AssistantControllerTest {

    @Test
    void writesStructuredProviderErrorsToTheSseStream() throws Exception {
        AssistantService assistant = mock(AssistantService.class);
        AssistantProviderErrorMapper errorMapper = mock(AssistantProviderErrorMapper.class);
        AssistantProperties properties = new AssistantProperties();

        when(assistant.stream(any(UUID.class), eq("hello")))
                .thenReturn(new AssistantStream(Flux.error(new IllegalStateException("provider failed")), Set::of));
        when(errorMapper.map(any()))
                .thenReturn(new AssistantErrorPayload("PROVIDER_UNAVAILABLE", "Assistant provider is unavailable", null));

        MockMvc mockMvc = standaloneSetup(new AssistantController(assistant, properties, errorMapper)).build();

        MvcResult result = mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "conversationId": "5c970c0e-d6b9-4e3c-8120-f8d496d654a5",
                                  "message": "hello"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getAsyncResult(1_000);
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":\"PROVIDER_UNAVAILABLE\"");
        assertThat(body).contains("\"message\":\"Assistant provider is unavailable\"");
        assertThat(body).doesNotContain("retryAfterSeconds");
    }

    @Test
    void quotaRejectionReturnsStructured429BeforeOpeningTheStream() throws Exception {
        AssistantService assistant = mock(AssistantService.class);
        Instant resetAt = Instant.parse("2026-11-23T03:00:00Z");
        when(assistant.stream(any(UUID.class), eq("hello"))).thenThrow(new AssistantQuotaExceededException(
                AssistantQuotaExceededException.Reason.DAILY_QUOTA, 3600L, resetAt));

        MockMvc mockMvc = standaloneSetup(new AssistantController(assistant, new AssistantProperties(),
                mock(AssistantProviderErrorMapper.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"conversationId": "5c970c0e-d6b9-4e3c-8120-f8d496d654a5", "message": "hello"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ASSISTANT_DAILY_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3600))
                .andExpect(jsonPath("$.retryAt").value("2026-11-23T03:00:00Z"))
                .andExpect(request().asyncNotStarted());
    }

    @Test
    void exposesTheCurrentUsersDailyUsage() throws Exception {
        AssistantService assistant = mock(AssistantService.class);
        when(assistant.usage()).thenReturn(new AssistantUsageResponse(true, 30, 4, 26,
                Instant.parse("2026-11-23T03:00:00Z"), "America/Sao_Paulo"));

        MockMvc mockMvc = standaloneSetup(new AssistantController(assistant, new AssistantProperties(),
                mock(AssistantProviderErrorMapper.class))).build();

        mockMvc.perform(get("/api/v1/assistant/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimit").value(30))
                .andExpect(jsonPath("$.used").value(4))
                .andExpect(jsonPath("$.remaining").value(26))
                .andExpect(jsonPath("$.resetTimezone").value("America/Sao_Paulo"));
    }
}
