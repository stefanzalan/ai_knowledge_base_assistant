package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentChunkRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentRepository;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionEntity;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence.DocumentVersionRepository;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional persistence operations for the ingestion use case.
 * Each public method runs in its own transaction; the orchestrating
 * {@link DocumentIngestionService} deliberately keeps embedding/vector
 * calls outside of these transactions.
 */
@Service
public class DocumentIngestionStore {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final JdbcTemplate jdbcTemplate;

    public DocumentIngestionStore(DocumentRepository documentRepository,
                                  DocumentVersionRepository documentVersionRepository,
                                  DocumentChunkRepository documentChunkRepository,
                                  JdbcTemplate jdbcTemplate) {
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds or creates the logical document and the version that should
     * receive the content. Identical content (same checksum) is detected
     * here so no duplicate chunks or embeddings are produced.
     */
    @Transactional
    public IngestionPreparation prepare(
            String sourceUri,
            FinancialProduct product,
            String title,
            String originalFilename,
            String checksum
    ) {
        DocumentEntity document = documentRepository
                .findBySourceUriAndProductForUpdate(sourceUri, product)
                .orElseGet(() -> documentRepository.saveAndFlush(
                        new DocumentEntity(
                                title,
                                originalFilename,
                                sourceUri,
                                product,
                                DocumentStatus.UPLOADED
                        )
                ));

        var existing = documentVersionRepository
                .findByDocumentIdAndSourceChecksum(document.getId(), checksum);

        if (existing.isPresent()) {
            DocumentVersionEntity version = existing.get();

            return switch (version.getStatus()) {
                case PENDING_APPROVAL, ACTIVE, ARCHIVED ->
                        new IngestionPreparation(
                                document.getId(),
                                version.getId(),
                                version.getVersionNumber(),
                                product,
                                sourceUri,
                                title,
                                version.getStatus(),
                                true
                        );

                case FAILED -> {
                    version.setStatus(DocumentStatus.PROCESSING);
                    documentVersionRepository.save(version);

                    yield new IngestionPreparation(
                            document.getId(),
                            version.getId(),
                            version.getVersionNumber(),
                            product,
                            sourceUri,
                            title,
                            version.getStatus(),
                            false
                    );
                }

                default -> throw new ApiException(
                        HttpStatus.CONFLICT,
                        "An ingestion for this content is already in progress"
                );
            };
        }

        int nextVersion = documentVersionRepository
                .findTopByDocumentIdOrderByVersionNumberDesc(document.getId())
                .map(DocumentVersionEntity::getVersionNumber)
                .orElse(0) + 1;

        DocumentVersionEntity version = documentVersionRepository.save(
                new DocumentVersionEntity(
                        document,
                        nextVersion,
                        checksum,
                        DocumentStatus.PROCESSING,
                        false
                )
        );

        return new IngestionPreparation(
                document.getId(),
                version.getId(),
                version.getVersionNumber(),
                product,
                sourceUri,
                title,
                version.getStatus(),
                false
        );
    }

    /**
     * Persists chunk rows and returns them with their generated UUIDs.
     * Committed before vector insertion so {@code vector_store.id} can
     * reference {@code document_chunk.id}.
     */
    @Transactional
    public List<PersistedChunk> persistChunks(UUID versionId, List<ChunkContent> chunks) {
        DocumentVersionEntity version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("Document version not found: " + versionId));

        List<DocumentChunkEntity> entities = new ArrayList<>(chunks.size());
        for (ChunkContent chunk : chunks) {
            entities.add(new DocumentChunkEntity(version, chunk.index(), chunk.content()));
        }
        documentChunkRepository.saveAll(entities);

        List<PersistedChunk> persisted = new ArrayList<>(entities.size());
        for (DocumentChunkEntity entity : entities) {
            persisted.add(new PersistedChunk(entity.getId(), entity.getChunkIndex(), entity.getContent()));
        }
        return persisted;
    }

    /**
     * Marks the version as successfully processed (PENDING_APPROVAL),
     * makes it the single current version and aligns the document status.
     */
    @Transactional
    public void markIngested(UUID versionId) {
        DocumentVersionEntity initialVersion = documentVersionRepository.findById(versionId)
                .orElseThrow(() ->
                        new IllegalStateException("Document version not found: " + versionId));

        UUID documentId = initialVersion.getDocument().getId();

        // Serialize finalization operations for this logical document.
        DocumentEntity document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() ->
                        new IllegalStateException("Document not found: " + documentId));

        DocumentVersionEntity version = documentVersionRepository.findById(versionId)
                .orElseThrow(() ->
                        new IllegalStateException("Document version not found: " + versionId));

        // A newly ingested version is NOT published yet.
        version.setStatus(DocumentStatus.PENDING_APPROVAL);
        version.setCurrent(false);

        documentVersionRepository.save(version);

        // Keep the document ACTIVE if an older published version still exists.
        boolean hasActiveCurrentVersion = documentVersionRepository
                .findByDocumentIdAndCurrentTrue(documentId)
                .filter(current -> current.getStatus() == DocumentStatus.ACTIVE)
                .isPresent();

        document.setStatus(
                hasActiveCurrentVersion
                        ? DocumentStatus.ACTIVE
                        : DocumentStatus.PENDING_APPROVAL
        );

        documentRepository.save(document);
    }

    /**
     * Marks the version as FAILED and removes any chunks (and, via the
     * {@code ON DELETE CASCADE} foreign key, their vector rows) so no
     * incomplete data remains. The document is marked FAILED only when it
     * has no current version, keeping it consistent with its current version.
     */
    @Transactional
    public void markFailed(UUID versionId) {
        DocumentVersionEntity initialVersion = documentVersionRepository.findById(versionId)
                .orElse(null);

        if (initialVersion == null) {
            return;
        }

        UUID documentId = initialVersion.getDocument().getId();

        DocumentEntity document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() ->
                        new IllegalStateException("Document not found: " + documentId));

        DocumentVersionEntity version = documentVersionRepository.findById(versionId)
                .orElse(null);

        if (version == null) {
            return;
        }

        version.setStatus(DocumentStatus.FAILED);
        documentVersionRepository.save(version);

        documentChunkRepository.deleteAll(
                documentChunkRepository.findByDocumentVersionIdOrderByChunkIndex(version.getId())
        );

        boolean hasCurrentVersion = documentVersionRepository
                .findByDocumentIdAndCurrentTrue(documentId)
                .isPresent();

        if (!hasCurrentVersion) {
            document.setStatus(DocumentStatus.FAILED);
            documentRepository.save(document);
        }
    }

    /**
     * Retries {@link #prepare} once after a constraint violation, which
     * typically means a concurrent request created the document or version
     * first.
     */
    public IngestionPreparation prepareWithRetry(String sourceUri, FinancialProduct product,
                                                 String title, String originalFilename, String checksum) {
        try {
            return prepare(sourceUri, product, title, originalFilename, checksum);
        } catch (DataIntegrityViolationException e) {
            try {
                return prepare(sourceUri, product, title, originalFilename, checksum);
            } catch (DataIntegrityViolationException retryFailure) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Concurrent ingestion conflict; please retry the request");
            }
        }
    }

    /**
     * Activates a version only when it belongs to the given document.
     * Returns 404 for a nonexistent version or a version of another
     * document, so document/version mismatches are not distinguishable.
     */
    @Transactional
    public void activateVersion(UUID documentId, UUID versionId) {
        DocumentVersionEntity version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document version not found"));

        if (!version.getDocument().getId().equals(documentId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }

        activateVersion(versionId);
    }

    @Transactional
    public void activateVersion(UUID versionId) {
        DocumentVersionEntity initialVersion = documentVersionRepository.findById(versionId)
                .orElseThrow(() ->
                        new IllegalStateException("Document version not found: " + versionId));

        UUID documentId = initialVersion.getDocument().getId();

        // Lock the logical document to serialize version activation.
        DocumentEntity document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() ->
                        new IllegalStateException("Document not found: " + documentId));

        // Reload after acquiring the lock.
        DocumentVersionEntity version = documentVersionRepository.findById(versionId)
                .orElseThrow(() ->
                        new IllegalStateException("Document version not found: " + versionId));

        if (version.getStatus() != DocumentStatus.PENDING_APPROVAL) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Only PENDING_APPROVAL versions can be activated"
            );
        }

        // Demote the previous published version first.
        documentVersionRepository.clearCurrentExcept(documentId, versionId);

        // Publish the approved version.
        version.setStatus(DocumentStatus.ACTIVE);
        version.setCurrent(true);

        documentVersionRepository.save(version);

        // Keep vector metadata consistent with the relational activation in
        // the same transaction: only the activated version's chunks become
        // retrievable; every other version of this document is excluded.
        jdbcTemplate.update(
                """
                UPDATE vector_store v
                SET metadata = jsonb_set(COALESCE(v.metadata, '{}'::jsonb), '{retrievalEligible}', to_jsonb(dv.id = ?::uuid))
                FROM document_chunk c
                JOIN document_version dv ON dv.id = c.document_version_id
                WHERE v.id = c.id
                  AND dv.document_id = ?::uuid
                """,
                versionId, documentId
        );

        document.setStatus(DocumentStatus.ACTIVE);
        documentRepository.save(document);
    }
}
