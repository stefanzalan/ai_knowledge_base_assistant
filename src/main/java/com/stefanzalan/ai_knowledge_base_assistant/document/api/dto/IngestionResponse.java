package com.stefanzalan.ai_knowledge_base_assistant.document.api.dto;

import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;

import java.util.UUID;

/**
 * Response payload for a document ingestion request. Never exposes
 * filesystem paths or internal storage details.
 */
public record IngestionResponse(
        UUID documentId,
        UUID documentVersionId,
        int versionNumber,
        DocumentStatus status,
        int chunkCount,
        boolean alreadyIngested) {

    public static IngestionResponse from(IngestionResult result) {
        return new IngestionResponse(
                result.documentId(),
                result.documentVersionId(),
                result.versionNumber(),
                result.status(),
                result.chunkCount(),
                result.alreadyIngested());
    }
}
