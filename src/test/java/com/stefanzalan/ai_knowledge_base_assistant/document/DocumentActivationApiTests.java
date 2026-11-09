package com.stefanzalan.ai_knowledge_base_assistant.document;

import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionService;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionCommand;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /api/v1/admin/documents/{documentId}/
 * versions/{versionId}/activate} against real PostgreSQL + pgvector.
 * The {@link EmbeddingModel} is mocked so ingestion works without Ollama;
 * relational state and vector metadata are verified directly via JDBC.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class DocumentActivationApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_test")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
        registry.add("app.ingestion.storage-directory", () -> createTempDir().toString());
        registry.add("app.ingestion.chunk-size", () -> "300");
        registry.add("app.ingestion.chunk-overlap", () -> "50");
    }

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void stubEmbeddings() {
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            return documents.stream().map(d -> embedding()).toList();
        });
        when(embeddingModel.embed(anyString())).thenReturn(embedding());
    }

    private static float[] embedding() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.01f);
        return vector;
    }

    private static java.nio.file.Path createTempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("kb-activation-test");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private IngestionResult ingest(String sourceUri, String content) {
        return ingestionService.ingest(new IngestionCommand(
                "Doc " + sourceUri, FinancialProduct.HOME_LOAN, sourceUri, "doc.md",
                content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String markdown(String heading, int paragraphs) {
        StringBuilder sb = new StringBuilder("# ").append(heading).append("\n\n");
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("Paragraph ").append(i).append(" about ").append(heading)
                    .append(" with enough text to produce multiple chunks.\n\n");
        }
        return sb.toString();
    }

    private void activate(UUID documentId, UUID versionId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/documents/{documentId}/versions/{versionId}/activate",
                        documentId, versionId))
                .andExpect(status().isNoContent());
    }

    private String versionStatus(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM document_version WHERE id = ?", String.class, versionId);
    }

    private boolean versionIsCurrent(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_current FROM document_version WHERE id = ?", Boolean.class, versionId);
    }

    private String documentStatus(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, documentId);
    }

    private long eligibleVectorCount(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store v JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ? "
                        + "AND (v.metadata->>'retrievalEligible')::boolean = true",
                Long.class, versionId);
    }

    @Test
    void successfulActivationMakesVersionCurrentAndEligible() throws Exception {
        IngestionResult result = ingest("kb://activate/success", markdown("Success Doc", 4));
        assertThat(versionStatus(result.documentVersionId())).isEqualTo("PENDING_APPROVAL");
        assertThat(eligibleVectorCount(result.documentVersionId())).isZero();

        activate(result.documentId(), result.documentVersionId());

        assertThat(versionStatus(result.documentVersionId())).isEqualTo("ACTIVE");
        assertThat(versionIsCurrent(result.documentVersionId())).isTrue();
        assertThat(documentStatus(result.documentId())).isEqualTo("ACTIVE");
        assertThat(eligibleVectorCount(result.documentVersionId())).isEqualTo(result.chunkCount());
    }

    @Test
    void versionBelongingToAnotherDocumentReturns404() throws Exception {
        IngestionResult docA = ingest("kb://activate/doc-a", markdown("Doc A", 3));
        IngestionResult docB = ingest("kb://activate/doc-b", markdown("Doc B", 3));

        mockMvc.perform(post("/api/v1/admin/documents/{documentId}/versions/{versionId}/activate",
                        docA.documentId(), docB.documentVersionId()))
                .andExpect(status().isNotFound());

        // Nothing changed for either document.
        assertThat(versionStatus(docB.documentVersionId())).isEqualTo("PENDING_APPROVAL");
        assertThat(eligibleVectorCount(docB.documentVersionId())).isZero();
    }

    @Test
    void nonexistentVersionReturns404() throws Exception {
        IngestionResult doc = ingest("kb://activate/missing", markdown("Missing", 3));

        mockMvc.perform(post("/api/v1/admin/documents/{documentId}/versions/{versionId}/activate",
                        doc.documentId(), UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void activatingNewVersionDemotesPreviousAndFlipsEligibility() throws Exception {
        IngestionResult v1 = ingest("kb://activate/versioned", markdown("V1", 4));
        activate(v1.documentId(), v1.documentVersionId());
        assertThat(eligibleVectorCount(v1.documentVersionId())).isEqualTo(v1.chunkCount());

        IngestionResult v2 = ingest("kb://activate/versioned",
                markdown("V2", 4) + "\nChanged content.\n");
        activate(v2.documentId(), v2.documentVersionId());

        // Previous version is demoted: no longer current, no longer eligible.
        assertThat(versionIsCurrent(v1.documentVersionId())).isFalse();
        assertThat(eligibleVectorCount(v1.documentVersionId())).isZero();

        // New version is the single current ACTIVE version.
        assertThat(versionStatus(v2.documentVersionId())).isEqualTo("ACTIVE");
        assertThat(versionIsCurrent(v2.documentVersionId())).isTrue();
        assertThat(documentStatus(v2.documentId())).isEqualTo("ACTIVE");
        assertThat(eligibleVectorCount(v2.documentVersionId())).isEqualTo(v2.chunkCount());
    }

    @Test
    void activatingNonPendingVersionReturns409AndLeavesStateUnchanged() throws Exception {
        IngestionResult result = ingest("kb://activate/guard", markdown("Guard", 3));
        activate(result.documentId(), result.documentVersionId());

        long eligibleBefore = eligibleVectorCount(result.documentVersionId());

        mockMvc.perform(post("/api/v1/admin/documents/{documentId}/versions/{versionId}/activate",
                        result.documentId(), result.documentVersionId()))
                .andExpect(status().isConflict());

        // Relational status and vector metadata are unchanged.
        assertThat(versionStatus(result.documentVersionId())).isEqualTo("ACTIVE");
        assertThat(versionIsCurrent(result.documentVersionId())).isTrue();
        assertThat(eligibleVectorCount(result.documentVersionId())).isEqualTo(eligibleBefore);
    }

    @Test
    void malformedUuidPathVariableReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/documents/{documentId}/versions/{versionId}/activate",
                        "not-a-uuid", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }
}
