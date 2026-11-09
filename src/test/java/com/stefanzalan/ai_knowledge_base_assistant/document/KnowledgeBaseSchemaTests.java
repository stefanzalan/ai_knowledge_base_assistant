package com.stefanzalan.ai_knowledge_base_assistant.document;

import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionRepository;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Flyway-owned knowledge base schema (V1 + V2) and the JPA
 * persistence layer against a real PostgreSQL instance with pgvector.
 * No Ollama instance is required: AI beans are created but never invoked.
 */
@SpringBootTest
@Testcontainers
@Transactional
class KnowledgeBaseSchemaTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_test")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Test
    void flywayAppliedV1AndV2Successfully() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);

        assertThat(applied).contains("1", "2");
    }

    @Test
    void requiredTablesAndConstraintsExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN "
                        + "('document', 'document_version', 'document_chunk', 'vector_store')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "document", "document_version", "document_chunk", "vector_store");

        Integer foreignKeys = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "JOIN pg_namespace n ON t.relnamespace = n.oid "
                        + "WHERE n.nspname = 'public' AND c.contype = 'f' "
                        + "AND t.relname IN ('document_version', 'document_chunk', 'vector_store')",
                Integer.class);

        assertThat(foreignKeys).isEqualTo(3);
    }

    @Test
    void vectorStoreEmbeddingHas1024Dimensions() {
        Integer dimensions = jdbcTemplate.queryForObject(
                "SELECT a.atttypmod FROM pg_attribute a "
                        + "JOIN pg_class c ON a.attrelid = c.oid "
                        + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                        + "WHERE n.nspname = 'public' AND c.relname = 'vector_store' "
                        + "AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped",
                Integer.class);

        assertThat(dimensions).isEqualTo(1024);
    }

    @Test
    void documentPersistsWithLifecycleStatus() {
        DocumentEntity document = documentRepository.saveAndFlush(new DocumentEntity(
                "Home Loan FAQ", "home-loan-faq.md", "kb://documents/home-loan-faq",
                FinancialProduct.HOME_LOAN, DocumentStatus.PENDING_APPROVAL));

        DocumentEntity reloaded = documentRepository.findById(document.getId()).orElseThrow();

        assertThat(reloaded.getTitle()).isEqualTo("Home Loan FAQ");
        assertThat(reloaded.getOriginalFilename()).isEqualTo("home-loan-faq.md");
        assertThat(reloaded.getSourceUri()).isEqualTo("kb://documents/home-loan-faq");
        assertThat(reloaded.getProduct()).isEqualTo(FinancialProduct.HOME_LOAN);
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void documentVersionsAssociateWithDocumentAndTrackCurrent() {
        DocumentEntity document = documentRepository.save(new DocumentEntity(
                "Personal Loan Terms", "personal-loan.md", "kb://documents/personal-loan",
                FinancialProduct.PERSONAL_LOAN, DocumentStatus.ACTIVE));

        DocumentVersionEntity v1 = documentVersionRepository.save(new DocumentVersionEntity(
                document, 1, "checksum-v1", DocumentStatus.ARCHIVED, false));
        DocumentVersionEntity v2 = documentVersionRepository.save(new DocumentVersionEntity(
                document, 2, "checksum-v2", DocumentStatus.ACTIVE, true));

        List<DocumentVersionEntity> versions =
                documentVersionRepository.findByDocumentIdOrderByVersionNumber(document.getId());

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getId()).isEqualTo(v1.getId());
        assertThat(versions.get(1).getId()).isEqualTo(v2.getId());
        assertThat(versions.get(1).getDocument().getId()).isEqualTo(document.getId());
        assertThat(versions.get(1).getSourceChecksum()).isEqualTo("checksum-v2");

        DocumentVersionEntity current = documentVersionRepository
                .findByDocumentIdAndCurrentTrue(document.getId())
                .orElseThrow();
        assertThat(current.getId()).isEqualTo(v2.getId());
    }

    @Test
    void documentChunksPreserveOrderAndLinkToVersion() {
        DocumentEntity document = documentRepository.save(new DocumentEntity(
                "Bank Account Fees", "bank-account.md", "kb://documents/bank-account",
                FinancialProduct.BANK_ACCOUNT, DocumentStatus.ACTIVE));
        DocumentVersionEntity version = documentVersionRepository.save(new DocumentVersionEntity(
                document, 1, "checksum-fees", DocumentStatus.ACTIVE, true));

        documentChunkRepository.save(new DocumentChunkEntity(version, 1, "first chunk"));
        documentChunkRepository.save(new DocumentChunkEntity(version, 0, "zeroth chunk"));
        documentChunkRepository.save(new DocumentChunkEntity(version, 2, "third chunk"));

        List<DocumentChunkEntity> chunks =
                documentChunkRepository.findByDocumentVersionIdOrderByChunkIndex(version.getId());

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getChunkIndex()).isZero();
        assertThat(chunks.get(0).getContent()).isEqualTo("zeroth chunk");
        assertThat(chunks.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(chunks.get(2).getChunkIndex()).isEqualTo(2);
        assertThat(chunks.get(0).getDocumentVersion().getId()).isEqualTo(version.getId());
        assertThat(chunks.get(0).getId()).isNotNull();
    }

    @Test
    void vectorStoreRowCanReferenceDocumentChunk() {
        DocumentEntity document = documentRepository.save(new DocumentEntity(
                "Traceability", "trace.md", "kb://documents/trace",
                FinancialProduct.HOME_LOAN, DocumentStatus.ACTIVE));
        DocumentVersionEntity version = documentVersionRepository.save(new DocumentVersionEntity(
                document, 1, "checksum-trace", DocumentStatus.ACTIVE, true));
        DocumentChunkEntity chunk = documentChunkRepository.saveAndFlush(
                new DocumentChunkEntity(version, 0, "traceable chunk"));

        // Simulates what Spring AI PgVectorStore will insert: the vector row
        // shares the chunk UUID as its primary key / foreign key.
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) "
                        + "VALUES (?, ?, ?::jsonb, ?::vector)",
                chunk.getId(),
                chunk.getContent(),
                "{\"document_id\":\"" + document.getId() + "\",\"chunk_index\":0}",
                "[" + "0,".repeat(1023) + "0]");

        UUID vectorId = jdbcTemplate.queryForObject(
                "SELECT id FROM vector_store WHERE id = ?", UUID.class, chunk.getId());
        assertThat(vectorId).isEqualTo(chunk.getId());

        Integer linkedChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store v "
                        + "JOIN document_chunk dc ON v.id = dc.id "
                        + "JOIN document_version dv ON dc.document_version_id = dv.id "
                        + "JOIN document d ON dv.document_id = d.id "
                        + "WHERE d.id = ?",
                Integer.class, document.getId());
        assertThat(linkedChunks).isEqualTo(1);
    }
}
