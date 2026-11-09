package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;

import java.util.UUID;

/**
 * Result of the transactional "prepare" step: the logical document and the
 * version that should receive the new content (or that was found identical).
 */
public record IngestionPreparation(
        UUID documentId,
        UUID versionId,
        int versionNumber,
        FinancialProduct product,
        String sourceUri,
        String title,
        DocumentStatus versionStatus,
        boolean alreadyIngested) {
}
