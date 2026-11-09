package com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/chat/ask}.
 *
 * <p>{@code insufficientEvidence} is {@code true} when no eligible source
 * material was found or when the model could not ground its answer in the
 * supplied references; in that case {@code citations} is empty.</p>
 */
public record AskResponse(
        String answer,
        boolean insufficientEvidence,
        List<CitationResponse> citations) {
}
