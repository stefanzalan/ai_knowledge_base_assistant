package com.stefanzalan.ai_knowledge_base_assistant.retrieval;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionService;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionStore;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionCommand;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end retrieval tests against real PostgreSQL + pgvector.
 * The {@link EmbeddingModel} is mocked (non-zero constant vectors, so cosine
 * distance is 0 and every eligible chunk passes the similarity threshold);
 * the real Spring AI {@code PgVectorStore} performs the filtered vector query,
 * so the JSONB metadata filtering is exercised for real.
 *
 * <p>Tests share one database, so "not retrievable" assertions are scoped to
 * the specific document version under test rather than to an empty result.
 * {@code PERSONAL_LOAN} is reserved for the empty-result test.</p>
 */
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class RetrievalPipelineTests {

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
        // All mocked embeddings are identical, so similarity ties make top-K
        // selection arbitrary; a large top-K keeps assertions deterministic.
        registry.add("app.retrieval.top-k", () -> "100");
    }

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentIngestionStore ingestionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            return java.nio.file.Files.createTempDirectory("kb-retrieval-test");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private IngestionResult ingest(String sourceUri, FinancialProduct product, String content) {
        return ingestionService.ingest(new IngestionCommand(
                "Doc " + sourceUri, product, sourceUri, "doc.md",
                content.getBytes(StandardCharsets.UTF_8)));
    }

    private IngestionResult ingestAndActivate(String sourceUri, FinancialProduct product, String content) {
        IngestionResult result = ingest(sourceUri, product, content);
        ingestionStore.activateVersion(result.documentVersionId());
        return result;
    }

    private static String markdown(String heading, int paragraphs) {
        StringBuilder sb = new StringBuilder("# ").append(heading).append("\n\n");
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("Paragraph ").append(i).append(" about ").append(heading)
                    .append(" with enough text to produce multiple retrieval chunks.\n\n");
        }
        return sb.toString();
    }

    private JsonNode search(String query, FinancialProduct product) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + query + "\",\"product\":\"" + product.name() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(mvcResult.getResponse().getContentAsString());
    }

    private List<String> returnedVersionIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        for (JsonNode chunk : response.get("chunks")) {
            ids.add(chunk.get("documentVersionId").asText());
        }
        return ids;
    }

    private void setVersionStatus(UUID versionId, String status) {
        jdbcTemplate.update("UPDATE document_version SET status = ? WHERE id = ?", status, versionId);
    }

    private long eligibleVectorCount(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store v JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ? "
                        + "AND (v.metadata->>'retrievalEligible')::boolean = true",
                Long.class, versionId);
    }

    private long ineligibleVectorCount(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store v JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ? "
                        + "AND COALESCE((v.metadata->>'retrievalEligible')::boolean, false) = false",
                Long.class, versionId);
    }

    @Test
    void activeCurrentVersionIsRetrievableWithSourceMetadata() throws Exception {
        IngestionResult result = ingestAndActivate(
                "kb://retrieval/active", FinancialProduct.HOME_LOAN, markdown("Active Doc", 6));

        JsonNode response = search("What are the requirements?", FinancialProduct.HOME_LOAN);

        assertThat(response.get("query").asText()).isEqualTo("What are the requirements?");
        assertThat(response.get("product").asText()).isEqualTo("HOME_LOAN");
        assertThat(response.get("chunks")).isNotEmpty();

        // Other tests may have activated HOME_LOAN documents too; assert on
        // the chunks belonging to this version specifically.
        List<JsonNode> ownChunks = new ArrayList<>();
        for (JsonNode chunk : response.get("chunks")) {
            if (chunk.get("documentVersionId").asText()
                    .equals(result.documentVersionId().toString())) {
                ownChunks.add(chunk);
            }
        }
        assertThat(ownChunks).isNotEmpty();
        for (JsonNode chunk : ownChunks) {
            assertThat(chunk.get("documentId").asText())
                    .isEqualTo(result.documentId().toString());
            assertThat(chunk.get("title").asText()).isEqualTo("Doc kb://retrieval/active");
            assertThat(chunk.get("sourceUri").asText()).isEqualTo("kb://retrieval/active");
            assertThat(chunk.get("chunkIndex").isInt());
            assertThat(chunk.get("similarityScore").asDouble()).isGreaterThan(0.9);
            assertThat(chunk.get("content").asText()).isNotBlank();
        }
    }

    @Test
    void pendingApprovalVersionIsNotRetrievable() throws Exception {
        IngestionResult result = ingest(
                "kb://retrieval/pending", FinancialProduct.HOME_LOAN, markdown("Pending Doc", 4));

        JsonNode response = search("pending question", FinancialProduct.HOME_LOAN);

        assertThat(returnedVersionIds(response))
                .doesNotContain(result.documentVersionId().toString());
        assertThat(eligibleVectorCount(result.documentVersionId())).isZero();
    }

    @Test
    void processingVersionIsNotRetrievable() throws Exception {
        IngestionResult result = ingest(
                "kb://retrieval/processing", FinancialProduct.HOME_LOAN, markdown("Processing Doc", 4));
        setVersionStatus(result.documentVersionId(), "PROCESSING");

        JsonNode response = search("processing question", FinancialProduct.HOME_LOAN);

        assertThat(returnedVersionIds(response))
                .doesNotContain(result.documentVersionId().toString());
    }

    @Test
    void failedVersionIsNotRetrievable() throws Exception {
        IngestionResult result = ingest(
                "kb://retrieval/failed", FinancialProduct.HOME_LOAN, markdown("Failed Doc", 4));
        setVersionStatus(result.documentVersionId(), "FAILED");

        JsonNode response = search("failed question", FinancialProduct.HOME_LOAN);

        assertThat(returnedVersionIds(response))
                .doesNotContain(result.documentVersionId().toString());
    }

    @Test
    void archivedVersionIsNotRetrievable() throws Exception {
        IngestionResult result = ingest(
                "kb://retrieval/archived", FinancialProduct.HOME_LOAN, markdown("Archived Doc", 4));
        setVersionStatus(result.documentVersionId(), "ARCHIVED");

        JsonNode response = search("archived question", FinancialProduct.HOME_LOAN);

        assertThat(returnedVersionIds(response))
                .doesNotContain(result.documentVersionId().toString());
    }

    @Test
    void oldActiveNonCurrentVersionIsNotRetrievableAfterNewActivation() throws Exception {
        IngestionResult v1 = ingestAndActivate(
                "kb://retrieval/versioned", FinancialProduct.HOME_LOAN, markdown("V1 Content", 4));
        IngestionResult v2 = ingestAndActivate(
                "kb://retrieval/versioned", FinancialProduct.HOME_LOAN,
                markdown("V2 Content", 4) + "\nChanged paragraph.\n");

        JsonNode response = search("versioned question", FinancialProduct.HOME_LOAN);
        List<String> returned = returnedVersionIds(response);

        assertThat(returned).contains(v2.documentVersionId().toString());
        assertThat(returned).doesNotContain(v1.documentVersionId().toString());

        // Metadata flags were flipped transactionally with activation.
        assertThat(eligibleVectorCount(v1.documentVersionId())).isZero();
        assertThat(ineligibleVectorCount(v1.documentVersionId())).isEqualTo(v1.chunkCount());
        assertThat(eligibleVectorCount(v2.documentVersionId())).isEqualTo(v2.chunkCount());
    }

    @Test
    void documentFromAnotherProductIsNotRetrievable() throws Exception {
        IngestionResult bankAccount = ingestAndActivate(
                "kb://retrieval/bank-account", FinancialProduct.BANK_ACCOUNT, markdown("Bank Doc", 4));

        JsonNode homeLoanResponse = search("bank question", FinancialProduct.HOME_LOAN);
        assertThat(returnedVersionIds(homeLoanResponse))
                .doesNotContain(bankAccount.documentVersionId().toString());

        JsonNode bankAccountResponse = search("bank question", FinancialProduct.BANK_ACCOUNT);
        assertThat(returnedVersionIds(bankAccountResponse))
                .contains(bankAccount.documentVersionId().toString());
    }

    @Test
    void failedActivationLeavesMetadataAndRelationalStateConsistent() throws Exception {
        IngestionResult v1 = ingestAndActivate(
                "kb://retrieval/activation-guard", FinancialProduct.HOME_LOAN, markdown("Guard V1", 3));
        IngestionResult v2 = ingest(
                "kb://retrieval/activation-guard", FinancialProduct.HOME_LOAN,
                markdown("Guard V2", 3) + "\nDifferent content.\n");

        long v1EligibleBefore = eligibleVectorCount(v1.documentVersionId());
        long v2EligibleBefore = eligibleVectorCount(v2.documentVersionId());

        // Activating a non-PENDING_APPROVAL version must fail.
        assertThatThrownBy(() -> ingestionStore.activateVersion(v1.documentVersionId()))
                .isInstanceOf(ApiException.class);

        // Nothing changed: relational status and vector metadata stay consistent.
        assertThat(eligibleVectorCount(v1.documentVersionId())).isEqualTo(v1EligibleBefore);
        assertThat(eligibleVectorCount(v2.documentVersionId())).isEqualTo(v2EligibleBefore);
        assertThat(ineligibleVectorCount(v2.documentVersionId())).isEqualTo(v2.chunkCount());
    }

    @Test
    void returnsEmptyChunksWhenNoEligibleDocumentExists() throws Exception {
        // PERSONAL_LOAN is never activated anywhere in this test class.
        JsonNode response = search("anything at all", FinancialProduct.PERSONAL_LOAN);

        assertThat(response.get("chunks")).isEmpty();
        assertThat(response.get("query").asText()).isEqualTo("anything at all");
        assertThat(response.get("product").asText()).isEqualTo("PERSONAL_LOAN");
    }

    @Test
    void rejectsBlankQuery() throws Exception {
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingProduct() throws Exception {
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"valid question\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooLongQuery() throws Exception {
        String longQuery = "x".repeat(2001);
        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + longQuery + "\",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseShapeMatchesContract() throws Exception {
        ingestAndActivate("kb://retrieval/shape", FinancialProduct.HOME_LOAN, markdown("Shape", 4));

        mockMvc.perform(post("/api/v1/retrieval/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"shape question\",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("shape question"))
                .andExpect(jsonPath("$.product").value("HOME_LOAN"))
                .andExpect(jsonPath("$.chunks[0].content").isString())
                .andExpect(jsonPath("$.chunks[0].documentId").isString())
                .andExpect(jsonPath("$.chunks[0].documentVersionId").isString())
                .andExpect(jsonPath("$.chunks[0].title").isString())
                .andExpect(jsonPath("$.chunks[0].sourceUri").isString())
                .andExpect(jsonPath("$.chunks[0].chunkIndex").isNumber())
                .andExpect(jsonPath("$.chunks[0].similarityScore").isNumber());
    }
}
