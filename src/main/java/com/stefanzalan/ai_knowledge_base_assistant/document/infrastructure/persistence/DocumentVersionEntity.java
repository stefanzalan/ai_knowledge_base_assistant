package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * JPA persistence model for an immutable version of a document.
 * A new version never overwrites the previous one; at most one version
 * per document is flagged {@code isCurrent} (enforced by a partial
 * unique index in the database).
 */
@Entity
@Table(name = "document_version")
public class DocumentVersionEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "source_checksum", nullable = false, length = 128)
    private String sourceChecksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentVersionEntity() {
    }

    public DocumentVersionEntity(DocumentEntity document, int versionNumber,
                                 String sourceChecksum, DocumentStatus status, boolean current) {
        this.document = document;
        this.versionNumber = versionNumber;
        this.sourceChecksum = sourceChecksum;
        this.status = status;
        this.current = current;
    }

    public UUID getId() {
        return id;
    }

    public DocumentEntity getDocument() {
        return document;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
