package com.jobtracker.unit;

import com.jobtracker.dto.assistant.AssistantApplicationView;
import com.jobtracker.service.semantic.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {
    private static AssistantApplicationView app(String name) {
        return new AssistantApplicationView(UUID.nameUUIDFromBytes(name.getBytes()), name, "Org", null, null,
                null, null, 0, null, null, false, null);
    }

    @Test
    void exactKeywordHitStaysOnTopAndSemanticOnlyHitsAreAdded() {
        AssistantApplicationView exact = app("RabbitMQ Engineer");
        AssistantApplicationView related = app("Event-driven Backend");
        AssistantApplicationView alsoKeyword = app("RabbitMQ Support");

        List<AssistantApplicationView> fused = ReciprocalRankFusion.fuse(
                List.of(exact, alsoKeyword), List.of(exact, related), 60, 10);

        assertThat(fused).extracting(AssistantApplicationView::vacancyName)
                .containsExactly("RabbitMQ Engineer", "RabbitMQ Support", "Event-driven Backend");
        assertThat(fused).extracting(AssistantApplicationView::matchedBy)
                .containsExactly(ReciprocalRankFusion.BOTH, ReciprocalRankFusion.KEYWORD, ReciprocalRankFusion.SEMANTIC);
    }

    @Test
    void appearingInBothListsBeatsTopOfOneList() {
        AssistantApplicationView a = app("A");
        AssistantApplicationView b = app("B");
        AssistantApplicationView c = app("C");

        List<AssistantApplicationView> fused = ReciprocalRankFusion.fuse(List.of(a, b), List.of(c, b), 60, 10);

        assertThat(fused.getFirst().vacancyName()).isEqualTo("B");
        assertThat(fused).hasSize(3).extracting(AssistantApplicationView::id).doesNotHaveDuplicates();
    }

    @Test
    void respectsLimitAndHandlesEmptyLists() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(), 60, 5)).isEmpty();
        assertThat(ReciprocalRankFusion.fuse(List.of(app("A"), app("B"), app("C")), List.of(), 60, 2))
                .extracting(AssistantApplicationView::vacancyName).containsExactly("A", "B");
    }

    @Test
    void doesNotInventScores() {
        AssistantApplicationView a = app("A");
        assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(a), 60, 5).getFirst().relevance()).isNull();
    }
}
