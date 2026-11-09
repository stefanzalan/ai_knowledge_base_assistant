package com.stefanzalan.ai_knowledge_base_assistant.chat.application;

import java.util.UUID;

/**
 * Server-side citation built from {@code RetrievedChunk} metadata.
 * The chat model never supplies citation metadata directly.
 */
public record ChatCitation(
        UUID documentId,
        UUID documentVersionId,
        String title,
        String sourceUri,
        int chunkIndex) {
}
