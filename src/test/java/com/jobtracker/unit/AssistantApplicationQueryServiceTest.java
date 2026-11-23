package com.jobtracker.unit;

import com.jobtracker.config.AssistantProperties;
import com.jobtracker.repository.ApplicationRepository;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository;
import com.jobtracker.service.assistant.AssistantApplicationQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssistantApplicationQueryServiceTest {
    private final AssistantApplicationQueryRepository queries = mock(AssistantApplicationQueryRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final AssistantApplicationQueryService service = new AssistantApplicationQueryService(
            queries, applications, new AssistantProperties(),
                mock(com.jobtracker.service.semantic.SemanticSearchService.class),
                new com.jobtracker.config.SemanticSearchProperties(),
                mock(com.jobtracker.repository.semantic.SemanticEmbeddingRepository.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void allowsStructuredSearchWithoutTextQuery(String query) {
        UUID userId = UUID.randomUUID();

        assertThatCode(() -> service.search(userId, query, "CWI", null, null,
                null, null, false, null)).doesNotThrowAnyException();

        verify(queries).search(userId, null, "CWI", null, null,
                null, null, false, 10, false);
    }

    @Test
    void rejectsSearchWithoutTextOrMeaningfulStructuredFilters() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.search(userId, null, null, null, null,
                null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("query or at least one structured filter is required");
    }
}
