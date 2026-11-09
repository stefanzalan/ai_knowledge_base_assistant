package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;

import java.util.UUID;

/**
 * Outcome of a document ingestion request.
 */
public record IngestionResult(
        UUID documentId,
        UUID documentVersionId,
        int versionNumber,
        DocumentStatus status,
        int chunkCount,
        boolean alreadyIngested) {
}
