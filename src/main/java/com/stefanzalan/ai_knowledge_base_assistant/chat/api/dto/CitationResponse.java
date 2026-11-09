package com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto;

import java.util.UUID;

/**
 * A citation pointing at the exact retrieved chunk that supports the
 * answer. Built exclusively from server-side source metadata — never
 * from model-generated values.
 */
public record CitationResponse(
        UUID documentId,
        UUID documentVersionId,
        String title,
        String sourceUri,
        int chunkIndex) {
}
