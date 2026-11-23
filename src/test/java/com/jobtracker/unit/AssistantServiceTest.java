package com.jobtracker.unit;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.exception.AssistantQuotaExceededException;
import com.jobtracker.exception.BadRequestException;
import com.jobtracker.exception.ServiceUnavailableException;
import com.jobtracker.service.DashboardService;
import com.jobtracker.service.assistant.AssistantApplicationQueryService;
import com.jobtracker.service.assistant.AssistantProviderErrorMapper;
import com.jobtracker.service.assistant.AssistantQuotaService;
import com.jobtracker.service.assistant.AssistantService;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {
    @Test
    void rejectsBlankMessagesBeforeCallingAProvider() {
        AssistantQuotaService quota = untrackedQuota();
        AssistantService service = service(new AssistantProperties(), null, quota);
        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), " "))
                .isInstanceOf(BadRequestException.class);
        verify(quota, never()).reserve(any());
    }

    @Test
    void failsSafelyWhenDisabled() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(false);
        AssistantQuotaService quota = untrackedQuota();
        AssistantService service = service(properties, null, quota);
        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "How many applications?"))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(quota, never()).reserve(any());
    }

    @Test
    void quotaRejectionNeverReachesTheProvider() {
        AssistantProperties properties = enabled();
        ChatModel model = model();
        AssistantQuotaService quota = mock(AssistantQuotaService.class);
        when(quota.reserve(any())).thenThrow(new AssistantQuotaExceededException(
                AssistantQuotaExceededException.Reason.DAILY_QUOTA, 60L, null));

        AssistantService service = service(properties, model, quota);

        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "Oi"))
                .isInstanceOf(AssistantQuotaExceededException.class);
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void capsOutputTokensForAnyProvider() {
        AssistantProperties properties = enabled();
        properties.setMaxOutputTokens(256);
        ChatModel model = model();
        AssistantService service = service(properties, model, untrackedQuota());

        service.stream(UUID.randomUUID(), "Oi").content().blockLast();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(prompt.getValue().getOptions().getMaxTokens()).isEqualTo(256);
    }

    @Test
    void zeroMaxOutputTokensKeepsTheProviderDefault() {
        AssistantProperties properties = enabled();
        properties.setMaxOutputTokens(0);
        ChatModel model = model();
        AssistantService service = service(properties, model, untrackedQuota());

        service.stream(UUID.randomUUID(), "Oi").content().blockLast();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(prompt.getValue().getOptions().getMaxTokens()).isNull();
    }

    private static AssistantProperties enabled() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static ChatModel model() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))))));
        return model;
    }

    private AssistantService service(AssistantProperties properties, ChatModel model, AssistantQuotaService quota) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (model != null) factory.addBean("chatModel", model);
        SecurityUtils security = mock(SecurityUtils.class);
        when(security.getCurrentUserId()).thenReturn(UUID.randomUUID());
        return new AssistantService(factory.getBeanProvider(ChatModel.class),
                mock(AssistantApplicationQueryService.class), mock(DashboardService.class),
                security, properties, new SimpleMeterRegistry(),
                quota, new AssistantProviderErrorMapper());
    }

    private static AssistantQuotaService untrackedQuota() {
        AssistantQuotaService quota = mock(AssistantQuotaService.class);
        when(quota.reserve(any())).thenReturn(AssistantQuotaService.Reservation.untracked());
        return quota;
    }
}
