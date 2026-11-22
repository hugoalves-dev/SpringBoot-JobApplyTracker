package com.jobtracker.integration;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.entity.AssistantRequestReservation;
import com.jobtracker.entity.enums.AssistantReservationStatus;
import com.jobtracker.exception.AssistantQuotaExceededException;
import com.jobtracker.repository.assistant.AssistantQuotaLockRepository;
import com.jobtracker.repository.assistant.AssistantRequestReservationRepository;
import com.jobtracker.service.DashboardService;
import com.jobtracker.service.assistant.AssistantApplicationQueryService;
import com.jobtracker.service.assistant.AssistantProviderErrorMapper;
import com.jobtracker.service.assistant.AssistantQuotaService;
import com.jobtracker.service.assistant.AssistantService;
import com.jobtracker.util.SecurityUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** End-to-end accounting through AssistantService with a fake provider and the real quota store. */
@SpringBootTest
@ActiveProfiles("test")
class AssistantServiceQuotaIT {

    @Autowired
    private AssistantRequestReservationRepository reservations;

    @Autowired
    private AssistantQuotaLockRepository locks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AssistantProperties properties;
    private ChatModel model;
    private UUID userId;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.getQuota().setGlobalRateLimit(0);
        properties.getQuota().setGlobalDailyLimit(0);
        properties.getQuota().setRateLimit(0);

        model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());

        userId = UUID.randomUUID();
        SecurityUtils security = mock(SecurityUtils.class);
        when(security.getCurrentUserId()).thenReturn(userId);

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("chatModel", model);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AssistantQuotaService quota = new AssistantQuotaService(reservations, locks, properties, meters,
                transactionManager);
        service = new AssistantService(factory.getBeanProvider(ChatModel.class),
                mock(AssistantApplicationQueryService.class), mock(DashboardService.class), security,
                properties, meters, quota, new AssistantProviderErrorMapper());
    }

    @Test
    void completedStreamIsChargedWithReportedTokens() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("Olá", null),
                chunk(" mundo", new DefaultUsage(120, 30))));

        assertThat(service.stream(UUID.randomUUID(), "Oi").content().collectList().block())
                .containsExactly("Olá", " mundo");

        AssistantRequestReservation stored = single();
        assertThat(stored.getStatus()).isEqualTo(AssistantReservationStatus.COMPLETED);
        assertThat(stored.getPromptTokens()).isEqualTo(120);
        assertThat(stored.getCompletionTokens()).isEqualTo(30);
        assertThat(service.usage().used()).isEqualTo(1);
    }

    @Test
    void providerErrorBeforeAnyOutputIsRefunded() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("429 upstream")));

        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "Oi").content().blockLast())
                .hasMessageContaining("429 upstream");

        assertThat(single().getStatus()).isEqualTo(AssistantReservationStatus.REFUNDED);
        assertThat(service.usage().used()).isZero();
    }

    @Test
    void providerErrorAfterOutputIsCharged() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(chunk("parcial", null)),
                Flux.error(new IllegalStateException("stream broke"))));

        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "Oi").content().blockLast())
                .hasMessageContaining("stream broke");

        assertThat(single().getStatus()).isEqualTo(AssistantReservationStatus.FAILED);
        assertThat(service.usage().used()).isEqualTo(1);
    }

    @Test
    void clientDisconnectIsChargedAndFreesTheInFlightSlot() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(chunk("primeiro", null)),
                Flux.never()));

        assertThat(service.stream(UUID.randomUUID(), "Oi").content().take(1).blockLast()).isEqualTo("primeiro");

        assertThat(single().getStatus()).isEqualTo(AssistantReservationStatus.CANCELLED);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("ok", null)));
        assertThat(service.stream(UUID.randomUUID(), "De novo").content().blockLast()).isEqualTo("ok");
    }

    @Test
    void exhaustedDailyQuotaNeverReachesTheProvider() {
        properties.getQuota().setDailyLimit(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("ok", null)));

        service.stream(UUID.randomUUID(), "Primeira").content().blockLast();

        assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "Segunda"))
                .isInstanceOf(AssistantQuotaExceededException.class);
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void concurrentRequestFromAnotherTabIsRejectedWhileGenerating() {
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());

        var first = service.stream(UUID.randomUUID(), "Aba 1").content().subscribe();
        try {
            assertThatThrownBy(() -> service.stream(UUID.randomUUID(), "Aba 2"))
                    .isInstanceOfSatisfying(AssistantQuotaExceededException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo("ASSISTANT_REQUEST_IN_PROGRESS"));
        } finally {
            first.dispose();
        }
    }

    private AssistantRequestReservation single() {
        List<AssistantRequestReservation> all = reservations.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static ChatResponse chunk(String text, DefaultUsage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) metadata.usage(usage);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
    }
}
