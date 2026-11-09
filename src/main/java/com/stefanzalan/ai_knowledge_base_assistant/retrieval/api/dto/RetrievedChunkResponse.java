package com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto;

import java.util.UUID;

/**
 * A single retrieved chunk with its source reference, ready for citation.
 * {@code similarityScore} is the cosine similarity reported by the vector
 * store (1 - distance); it is never computed manually.
 */
public record RetrievedChunkResponse(
        String content,
        UUID documentId,
        UUID documentVersionId,
        String title,
        String sourceUri,
        int chunkIndex,
        Double similarityScore) {
}
