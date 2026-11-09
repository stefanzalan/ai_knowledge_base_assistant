package com.stefanzalan.ai_knowledge_base_assistant.document;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionService;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionCommand;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionRepository;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end ingestion tests against real PostgreSQL + pgvector.
 * The {@link EmbeddingModel} is mocked so no Ollama instance is required;
 * the real Spring AI {@code PgVectorStore} still performs the vector inserts.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class DocumentIngestionTests {

    private static final Path STORAGE_DIR = createTempDir();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_test")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
        registry.add("app.ingestion.storage-directory", STORAGE_DIR::toString);
        registry.add("app.ingestion.chunk-size", () -> "300");
        registry.add("app.ingestion.chunk-overlap", () -> "50");
    }

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void stubEmbeddings() {
        stubEmbeddingDimensions(1024);
    }

    private void stubEmbeddingDimensions(int dimensions) {
        // reset() is required: re-stubbing a method previously stubbed with
        // thenThrow would otherwise invoke the throwing answer during when().
        reset(embeddingModel);
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            return documents.stream().map(d -> new float[dimensions]).toList();
        });
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("kb-ingestion-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String markdown(String heading, int paragraphCount) {
        StringBuilder sb = new StringBuilder("# ").append(heading).append("\n\n");
        for (int i = 1; i <= paragraphCount; i++) {
            sb.append("Paragraph ").append(i).append(" of ").append(heading)
                    .append(" contains enough text to make the chunker produce multiple chunks.\n\n");
        }
        return sb.toString();
    }

    private IngestionCommand command(String sourceUri, String content) {
        return new IngestionCommand(
                "Test Document", FinancialProduct.HOME_LOAN, sourceUri, "test.md",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private long vectorCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
    }

    private long vectorCountForDocument(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = ?",
                Long.class, documentId.toString());
    }

    @Test
    void successfulIngestionPersistsDocumentVersionChunksAndVectors() {
        String content = markdown("Home Loans", 8);
        IngestionResult result = ingestionService.ingest(command("kb://docs/home-loan", content));

        assertThat(result.alreadyIngested()).isFalse();
        assertThat(result.status()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(result.chunkCount()).isGreaterThan(1);

        DocumentEntity document = documentRepository.findById(result.documentId()).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(document.getProduct()).isEqualTo(FinancialProduct.HOME_LOAN);

        DocumentVersionEntity version = documentVersionRepository.findById(result.documentVersionId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(version.isCurrent()).isFalse();
        assertThat(version.getVersionNumber()).isEqualTo(1);

        List<DocumentChunkEntity> chunks =
                documentChunkRepository.findByDocumentVersionIdOrderByChunkIndex(version.getId());
        assertThat(chunks).hasSize(result.chunkCount());
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
        }

        // Every chunk has exactly one vector row sharing its UUID.
        Long vectors = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store v JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ?",
                Long.class, version.getId());
        assertThat(vectors).isEqualTo(result.chunkCount());

        String product = jdbcTemplate.queryForObject(
                "SELECT v.metadata->>'product' FROM vector_store v "
                        + "JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ? LIMIT 1",
                String.class, version.getId());
        assertThat(product).isEqualTo("HOME_LOAN");
        String documentId = jdbcTemplate.queryForObject(
                "SELECT v.metadata->>'documentId' FROM vector_store v "
                        + "JOIN document_chunk c ON v.id = c.id "
                        + "WHERE c.document_version_id = ? LIMIT 1",
                String.class, version.getId());
        assertThat(UUID.fromString(documentId)).isEqualTo(result.documentId());
    }

    @Test
    void identicalContentIsNotIngestedTwice() {
        String content = markdown("Duplicate", 4);
        IngestionResult first = ingestionService.ingest(command("kb://docs/dup", content));
        long vectorsAfterFirst = vectorCount();

        IngestionResult second = ingestionService.ingest(command("kb://docs/dup", content));

        assertThat(second.alreadyIngested()).isTrue();
        assertThat(second.documentVersionId()).isEqualTo(first.documentVersionId());
        assertThat(vectorCount()).isEqualTo(vectorsAfterFirst);
        assertThat(documentVersionRepository
                .findByDocumentIdOrderByVersionNumber(first.documentId())).hasSize(1);
    }

    @Test
    void changedContentCreatesNewSequentialVersion() {
        String v1 = markdown("Versioned", 4);
        IngestionResult first = ingestionService.ingest(command("kb://docs/versioned", v1));

        String v2 = markdown("Versioned", 6) + "\nExtra paragraph.\n";
        IngestionResult second = ingestionService.ingest(command("kb://docs/versioned", v2));

        assertThat(second.alreadyIngested()).isFalse();
        assertThat(second.versionNumber()).isEqualTo(2);
        assertThat(second.documentId()).isEqualTo(first.documentId());

        List<DocumentVersionEntity> versions = documentVersionRepository
                .findByDocumentIdOrderByVersionNumber(first.documentId());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).isCurrent()).isFalse();
        assertThat(versions.get(1).isCurrent()).isFalse();

        // Historical version chunks and vectors are preserved.
        long oldChunks = documentChunkRepository
                .findByDocumentVersionIdOrderByChunkIndex(first.documentVersionId()).size();
        assertThat(oldChunks).isEqualTo(first.chunkCount());
    }

    @Test
    void embeddingFailureMarksVersionFailedAndLeavesNoPartialData() {
        when(embeddingModel.embed(anyList(), any(), any()))
                .thenThrow(new RuntimeException("Ollama connection refused"));

        assertThatThrownBy(() -> ingestionService.ingest(command("kb://docs/fail", markdown("Fail", 4))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        DocumentEntity document = documentRepository
                .findBySourceUriAndProduct("kb://docs/fail", FinancialProduct.HOME_LOAN).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);

        DocumentVersionEntity version = documentVersionRepository
                .findTopByDocumentIdOrderByVersionNumberDesc(document.getId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documentChunkRepository
                .findByDocumentVersionIdOrderByChunkIndex(version.getId())).isEmpty();
        assertThat(vectorCountForDocument(document.getId())).isZero();
    }

    @Test
    void invalidEmbeddingDimensionsAreRejectedAndCleanedUp() {
        stubEmbeddingDimensions(8);

        assertThatThrownBy(() -> ingestionService.ingest(command("kb://docs/baddims", markdown("Dims", 4))))
                .isInstanceOf(ApiException.class);

        DocumentEntity document = documentRepository
                .findBySourceUriAndProduct("kb://docs/baddims", FinancialProduct.HOME_LOAN).orElseThrow();
        DocumentVersionEntity version = documentVersionRepository
                .findTopByDocumentIdOrderByVersionNumberDesc(document.getId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documentChunkRepository
                .findByDocumentVersionIdOrderByChunkIndex(version.getId())).isEmpty();
        assertThat(vectorCountForDocument(document.getId())).isZero();
    }

    @Test
    void failedVersionCanBeReprocessed() {
        when(embeddingModel.embed(anyList(), any(), any()))
                .thenThrow(new RuntimeException("Ollama down"));
        assertThatThrownBy(() -> ingestionService.ingest(command("kb://docs/retry", markdown("Retry", 3))))
                .isInstanceOf(ApiException.class);

        stubEmbeddingDimensions(1024);
        IngestionResult retry = ingestionService.ingest(command("kb://docs/retry", markdown("Retry", 3)));

        assertThat(retry.alreadyIngested()).isFalse();
        assertThat(retry.status()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(retry.versionNumber()).isEqualTo(1);
    }

    @Test
    void apiAcceptsMarkdownUpload() throws Exception {
        String content = markdown("API", 5);

        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(new MockMultipartFile("file", "api.md", "text/markdown",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("title", "API Doc")
                        .param("product", "PERSONAL_LOAN")
                        .param("sourceUri", "kb://docs/api"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").exists())
                .andExpect(jsonPath("$.documentVersionId").exists())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.alreadyIngested").value(false))
                .andExpect(jsonPath("$.chunkCount").isNumber());
    }

    @Test
    void apiRejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0]))
                        .param("title", "Empty")
                        .param("product", "HOME_LOAN")
                        .param("sourceUri", "kb://docs/empty"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void apiRejectsNonMarkdownFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(new MockMultipartFile("file", "doc.txt", "text/plain", "hello".getBytes()))
                        .param("title", "Text")
                        .param("product", "HOME_LOAN")
                        .param("sourceUri", "kb://docs/txt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiRejectsMissingProduct() throws Exception {
        mockMvc.perform(multipart("/api/v1/admin/documents")
                        .file(new MockMultipartFile("file", "x.md", "text/markdown", "# X".getBytes()))
                        .param("title", "X")
                        .param("sourceUri", "kb://docs/noprod"))
                .andExpect(status().isBadRequest());
    }
}
