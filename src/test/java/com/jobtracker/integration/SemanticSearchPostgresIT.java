package com.jobtracker.integration;

import com.jobtracker.config.SemanticSearchProperties;
import com.jobtracker.dto.assistant.AssistantApplicationView;
import com.jobtracker.repository.assistant.AssistantApplicationQueryRepository;
import com.jobtracker.repository.semantic.SemanticEmbeddingRepository;
import com.jobtracker.service.BaseInformationService;
import com.jobtracker.service.semantic.EmbeddingProviderException;
import com.jobtracker.service.semantic.EmbeddingSyncService;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient;
import com.jobtracker.service.semantic.OpenRouterEmbeddingClient.EmbeddingBatch;
import com.jobtracker.service.semantic.ReciprocalRankFusion;
import com.jobtracker.service.semantic.SemanticSearchService;
import com.jobtracker.service.semantic.VectorLiterals;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the real Flyway chain (including pgvector) and exercises the hybrid SQL against PostgreSQL.
 * Uses a pgvector Testcontainer; set {@code -Dsemantic.it.jdbc-url=...} (plus -user/-password) to run
 * against an existing pgvector-enabled database instead. The target schema is wiped.
 */
class SemanticSearchPostgresIT {
    private static final int DIMS = 1024;
    private static final String MODEL = "qwen/qwen3-embedding-8b";
    private static PostgreSQLContainer<?> container;
    private static NamedParameterJdbcTemplate jdbc;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private final OpenRouterEmbeddingClient client = mock(OpenRouterEmbeddingClient.class);
    private SemanticEmbeddingRepository embeddings;
    private AssistantApplicationQueryRepository queries;
    private EmbeddingSyncService sync;
    private UUID alice;
    private UUID bob;

    @BeforeAll
    static void startDatabase() {
        String url = System.getProperty("semantic.it.jdbc-url");
        String user = System.getProperty("semantic.it.user", "postgres");
        String password = System.getProperty("semantic.it.password", "");
        if (url == null) {
            container = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"));
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/postgresql").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().execute("TRUNCATE users CASCADE");
        properties.setEnabled(true);
        properties.setApiKey("test");
        properties.setDimensions(DIMS);
        embeddings = new SemanticEmbeddingRepository(jdbc);
        queries = new AssistantApplicationQueryRepository(jdbc);
        SemanticSearchService semantic = new SemanticSearchService(properties, client, embeddings);
        sync = new EmbeddingSyncService(properties, semantic, embeddings, client, mock(BaseInformationService.class),
                new SimpleMeterRegistry());
        alice = user("alice");
        bob = user("bob");
    }

    @Test
    void flywayCreatesCompatibleVectorColumn() {
        assertThat(embeddings.columnDimensions()).isEqualTo(DIMS);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class)).isEqualTo(1);
    }

    @Test
    void semanticSearchNeverReturnsAnotherTenantsVectors() {
        UUID aliceApp = application(alice, "Backend Engineer", "ACME", "event-driven services", false);
        UUID bobApp = application(bob, "Backend Engineer", "ACME", "event-driven services", false);
        ready(aliceApp, alice, unit(0));
        ready(bobApp, bob, unit(0));

        List<AssistantApplicationView> results = semanticSearch(alice, unit(0), null);

        assertThat(results).extracting(AssistantApplicationView::id).containsExactly(aliceApp);
        assertThat(semanticSearch(bob, unit(0), null)).extracting(AssistantApplicationView::id).containsExactly(bobApp);
    }

    @Test
    void semanticSearchRespectsModelSpaceDistanceCutoffAndStructuredFilters() {
        UUID close = application(alice, "Platform Engineer", "ACME", "Kafka", false);
        UUID unrelated = application(alice, "Barista", "Cafe", "coffee", false);
        UUID otherOrg = application(alice, "Platform Engineer", "Globex", "Kafka", false);
        UUID archived = application(alice, "Platform Engineer", "ACME", "Kafka", true);
        UUID oldModel = application(alice, "Platform Engineer", "ACME", "Kafka", false);
        ready(close, alice, unit(0));
        ready(unrelated, alice, unit(1));
        ready(otherOrg, alice, unit(0));
        ready(archived, alice, unit(0));
        embeddings.upsertPending("JOB_APPLICATION", oldModel, alice, "x", "h", "PENDING", "old/model", DIMS, null);
        embeddings.markReady(id(oldModel), "h", "old/model", DIMS, VectorLiterals.toLiteral(unit(0)));

        assertThat(semanticSearch(alice, unit(0), "ACME")).extracting(AssistantApplicationView::id)
                .containsExactly(close);
        assertThat(semanticSearch(alice, unit(0), null)).extracting(AssistantApplicationView::id)
                .containsExactlyInAnyOrder(close, otherOrg);
    }

    @Test
    void hybridKeepsExactKeywordMatchesAndAddsSemanticRecall() {
        UUID exact = application(alice, "RabbitMQ Developer", "ACME", "queues", false);
        UUID related = application(alice, "Backend Developer", "Globex", "event-driven architecture", false);
        UUID unembedded = application(alice, "RabbitMQ Admin", "Initech", "ops", false);
        ready(exact, alice, mix(0, 1));
        ready(related, alice, unit(0));

        List<AssistantApplicationView> keyword = queries.search(alice, "RabbitMQ", null, null, null, null, null,
                false, 50, false);
        List<AssistantApplicationView> semantic = semanticSearch(alice, unit(0), null);
        List<AssistantApplicationView> fused = ReciprocalRankFusion.fuse(keyword, semantic, 60, 10);

        assertThat(keyword).extracting(AssistantApplicationView::id).containsExactlyInAnyOrder(exact, unembedded);
        assertThat(semantic).extracting(AssistantApplicationView::id).doesNotContain(unembedded);
        assertThat(fused).extracting(AssistantApplicationView::id).containsExactlyInAnyOrder(exact, related, unembedded);
        assertThat(fused.getFirst().id()).isEqualTo(exact);
        assertThat(fused).filteredOn(v -> v.id().equals(related))
                .singleElement().satisfies(v -> assertThat(v.matchedBy()).isEqualTo("SEMANTIC"));
    }

    @Test
    void profileMatchesUseOnlyTheUsersOwnProfileAndApplications() {
        UUID aliceProfile = profileDocument(alice, "Alice CV");
        UUID bobProfile = profileDocument(bob, "Bob CV");
        readyProfile(aliceProfile, alice, unit(0));
        readyProfile(bobProfile, bob, unit(5));
        UUID near = application(alice, "Java Engineer", "ACME", "Remote, Spring", false);
        UUID far = application(alice, "Designer", "ACME", "Figma", false);
        UUID bobsNear = application(bob, "Java Engineer", "ACME", "Remote", false);
        ready(near, alice, mix(0, 2));
        ready(far, alice, unit(3));
        ready(bobsNear, bob, unit(0));

        var rows = queries.profileMatches(alice, null, MODEL, DIMS, null, null, null, null, null, false, 10);

        assertThat(rows).extracting(r -> r.application().id()).containsExactly(near, far);
        assertThat(rows.getFirst().fullNote()).isEqualTo("Remote, Spring");
        assertThat(embeddings.findReadyProfileDocuments(alice, MODEL, DIMS, null, 100))
                .extracting(SemanticEmbeddingRepository.ProfileDocument::documentName).containsExactly("Alice CV");
        assertThat(embeddings.findReadyProfileDocuments(alice, MODEL, DIMS, bobProfile, 100)).isEmpty();
    }

    @Test
    void syncBackfillsIdempotentlyAndReEmbedsOnlyOnContentOrModelChange() {
        UUID app = application(alice, "Backend Engineer", "ACME", "Kafka", false);
        stubProvider();

        sync.runOnce();
        assertThat(status(app)).isEqualTo("READY");
        verify(client, times(1)).embed(any(), eq("document"));

        touch(app, "UPDATE job_applications SET status = 'INTERVIEW', updated_at = now() WHERE id = :id");
        sync.runOnce();
        verify(client, times(1)).embed(any(), eq("document"));
        assertThat(status(app)).isEqualTo("READY");

        touch(app, "UPDATE job_applications SET note = 'RabbitMQ', updated_at = now() WHERE id = :id");
        sync.runOnce();
        verify(client, times(2)).embed(any(), eq("document"));
        assertThat(content(app)).contains("RabbitMQ");

        properties.setModel("other/embedding-model");
        sync.runOnce();
        verify(client, times(3)).embed(any(), eq("document"));
        assertThat(jdbc.queryForObject("SELECT model FROM semantic_embeddings WHERE job_application_id = :id",
                Map.of("id", app), String.class)).isEqualTo("other/embedding-model");
    }

    @Test
    void providerFailuresAreRetriedWithBackoffAndLeasesPreventDoubleClaims() {
        UUID app = application(alice, "Backend Engineer", "ACME", "Kafka", false);
        when(client.embed(any(), any())).thenThrow(new EmbeddingProviderException("503"));

        sync.runOnce();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, attempts, last_error, next_attempt_at > now() AS deferred
                FROM semantic_embeddings WHERE job_application_id = :id""", Map.of("id", app));
        assertThat(row).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_error", "503").containsEntry("deferred", true);

        jdbc.update("UPDATE semantic_embeddings SET next_attempt_at = now() - interval '1 second'", Map.of());
        assertThat(embeddings.claimDue(MODEL, DIMS, 5, 300, 10)).hasSize(1);
        assertThat(embeddings.claimDue(MODEL, DIMS, 5, 300, 10)).isEmpty();

        reset(client);
        stubProvider();
        jdbc.update("UPDATE semantic_embeddings SET next_attempt_at = now() - interval '1 second'", Map.of());
        sync.runOnce();
        assertThat(status(app)).isEqualTo("READY");
    }

    @Test
    void exhaustedRowsStopRetryingAndEmptyContentIsNeverSent() {
        UUID failing = application(alice, "Backend Engineer", "ACME", "Kafka", false);
        UUID empty = application(alice, null, "", null, false);
        properties.setMaxAttempts(1);
        when(client.embed(any(), any())).thenThrow(new EmbeddingProviderException("400 bad input"));

        sync.runOnce();
        jdbc.update("UPDATE semantic_embeddings SET next_attempt_at = now() - interval '1 second'", Map.of());
        sync.runOnce();

        verify(client, times(1)).embed(any(), any());
        assertThat(status(empty)).isEqualTo("EMPTY");
        SemanticEmbeddingRepository.Coverage coverage = embeddings.coverage(MODEL, DIMS, 1);
        assertThat(coverage.exhausted()).isEqualTo(1);
        assertThat(coverage.emptyApplications()).isEqualTo(1);
        assertThat(coverage.applicationRatio()).isZero();
        assertThat(status(failing)).isEqualTo("FAILED");
    }

    @Test
    void deletingApplicationOrUserRemovesEmbeddings() {
        UUID app = application(alice, "Backend Engineer", "ACME", "Kafka", false);
        ready(app, alice, unit(0));
        application(bob, "x", "y", "z", false);

        jdbc.update("DELETE FROM job_applications WHERE id = :id", Map.of("id", app));
        assertThat(count()).isZero();

        UUID bobApp = jdbc.queryForObject("SELECT id FROM job_applications WHERE user_id = :u", Map.of("u", bob), UUID.class);
        ready(bobApp, bob, unit(0));
        jdbc.update("DELETE FROM users WHERE id = :id", Map.of("id", bob));
        assertThat(count()).isZero();
    }

    @Test
    void disabledProviderLeavesSyncIdle() {
        properties.setEnabled(false);
        application(alice, "Backend Engineer", "ACME", "Kafka", false);

        assertThat(sync.runOnce().ran()).isFalse();
        verify(client, never()).embed(any(), any());
        assertThat(count()).isZero();
    }

    private void stubProvider() {
        when(client.embed(any(), eq("document"))).thenAnswer(inv -> {
            List<float[]> vectors = new ArrayList<>();
            for (Object ignored : (List<?>) inv.getArgument(0)) vectors.add(unit(vectors.size()));
            return new EmbeddingBatch(vectors, 10, null);
        });
    }

    private List<AssistantApplicationView> semanticSearch(UUID userId, float[] vector, String organization) {
        return queries.semanticSearch(userId, VectorLiterals.toLiteral(vector), MODEL, DIMS, 0.65, organization,
                null, null, null, null, false, 10);
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, name, email, password_hash, created_at, updated_at)
                VALUES (:id, :name, :email, 'x', now(), now())""",
                Map.of("id", id, "name", name, "email", name + "-" + id + "@example.com"));
        return id;
    }

    private UUID application(UUID userId, String vacancy, String organization, String note, boolean archived) {
        UUID id = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("userId", userId);
        params.put("vacancy", vacancy);
        params.put("organization", organization);
        params.put("note", note);
        params.put("archived", archived);
        jdbc.update("""
                INSERT INTO job_applications (id, user_id, vacancy_name, organization, note, archived, status,
                    application_date, created_at, updated_at)
                VALUES (:id, :userId, :vacancy, :organization, :note, :archived, 'APPLIED', current_date, now(), now())""",
                params);
        return id;
    }

    private UUID profileDocument(UUID userId, String name) {
        UUID connection = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO google_drive_connections (id, user_id, google_account_id, google_email, access_token,
                    refresh_token, access_token_expires_at, granted_scopes, connected_at)
                VALUES (:id, :userId, :id, 'x@example.com', 't', 'r', now(), 's', now())""",
                Map.of("id", connection, "userId", userId));
        UUID doc = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO google_drive_base_information (id, connection_id, google_file_id, document_name, doc_type,
                    created_at, updated_at)
                VALUES (:id, :connection, :id, :name, 'GOOGLE_DOC', now(), now())""",
                Map.of("id", doc, "connection", connection, "name", name));
        return doc;
    }

    private void ready(UUID applicationId, UUID userId, float[] vector) {
        embeddings.upsertPending("JOB_APPLICATION", applicationId, userId, "text", "h", "PENDING", MODEL, DIMS, null);
        assertThat(embeddings.markReady(id(applicationId), "h", MODEL, DIMS, VectorLiterals.toLiteral(vector))).isTrue();
    }

    private void readyProfile(UUID baseInformationId, UUID userId, float[] vector) {
        embeddings.upsertPending("PROFILE_DOCUMENT", baseInformationId, userId, "Java, Spring, remote", "p",
                "PENDING", MODEL, DIMS, null);
        UUID id = jdbc.queryForObject("SELECT id FROM semantic_embeddings WHERE base_information_id = :id",
                Map.of("id", baseInformationId), UUID.class);
        assertThat(embeddings.markReady(id, "p", MODEL, DIMS, VectorLiterals.toLiteral(vector))).isTrue();
    }

    private UUID id(UUID applicationId) {
        return jdbc.queryForObject("SELECT id FROM semantic_embeddings WHERE job_application_id = :id",
                Map.of("id", applicationId), UUID.class);
    }

    private String status(UUID applicationId) {
        return jdbc.queryForObject("SELECT status FROM semantic_embeddings WHERE job_application_id = :id",
                Map.of("id", applicationId), String.class);
    }

    private String content(UUID applicationId) {
        return jdbc.queryForObject("SELECT content FROM semantic_embeddings WHERE job_application_id = :id",
                Map.of("id", applicationId), String.class);
    }

    private void touch(UUID applicationId, String sql) {
        jdbc.update(sql, new MapSqlParameterSource("id", applicationId));
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM semantic_embeddings", Map.of(), Long.class);
    }

    private static float[] unit(int index) {
        float[] v = new float[DIMS];
        v[index] = 1f;
        return v;
    }

    private static float[] mix(int a, int b) {
        float[] v = new float[DIMS];
        v[a] = 0.8f;
        v[b] = 0.6f;
        return v;
    }
}
