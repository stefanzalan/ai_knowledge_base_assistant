package com.stefanzalan.ai_knowledge_base_assistant.retrieval.application;

import java.util.UUID;

/**
 * A retrieved chunk with its source reference, mapped from the vector
 * store result. Internal application representation; the API layer
 * converts it to {@code RetrievedChunkResponse}.
 */
public record RetrievedChunk(
        String content,
        UUID documentId,
        UUID documentVersionId,
        String title,
        String sourceUri,
        int chunkIndex,
        Double similarityScore) {
}
