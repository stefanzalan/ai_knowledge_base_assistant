package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;

/**
 * Input for the document ingestion use case. The content is the raw
 * Markdown file bytes; the filename is metadata only and is never used
 * as a filesystem path.
 */
public record IngestionCommand(
        String title,
        FinancialProduct product,
        String sourceUri,
        String originalFilename,
        byte[] content) {
}
