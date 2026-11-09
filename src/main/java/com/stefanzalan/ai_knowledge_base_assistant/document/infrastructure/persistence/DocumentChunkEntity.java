package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence model for an ordered chunk of a document version.
 *
 * <p>The chunk's UUID is also used as the id of the corresponding
 * {@code vector_store} row, which references this table via a foreign
 * key. This makes every embedding relationally traceable to its
 * document, version and chunk position.</p>
 */
@Entity
@Table(name = "document_chunk")
public class DocumentChunkEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_version_id", nullable = false)
    private DocumentVersionEntity documentVersion;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(DocumentVersionEntity documentVersion, int chunkIndex, String content) {
        this.documentVersion = documentVersion;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public DocumentVersionEntity getDocumentVersion() {
        return documentVersion;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
