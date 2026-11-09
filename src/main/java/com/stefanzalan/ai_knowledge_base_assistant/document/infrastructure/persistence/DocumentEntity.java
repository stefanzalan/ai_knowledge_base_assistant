package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence model for a trusted knowledge source document.
 * Not exposed through the API layer; domain objects are introduced
 * when document use cases are implemented.
 */
@Entity
@Table(name = "document")
public class DocumentEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    @Column(name = "source_uri", nullable = false, length = 1000)
    private String sourceUri;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FinancialProduct product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentEntity() {
    }

    public DocumentEntity(String title, String originalFilename, String sourceUri,
                          FinancialProduct product, DocumentStatus status) {
        this.title = title;
        this.originalFilename = originalFilename;
        this.sourceUri = sourceUri;
        this.product = product;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public FinancialProduct getProduct() {
        return product;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
