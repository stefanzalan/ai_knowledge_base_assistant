package com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;

import java.util.List;

/**
 * Response body for {@code POST /api/v1/retrieval/search}.
 */
public record RetrievalResponse(
        String query,
        FinancialProduct product,
        List<RetrievedChunkResponse> chunks) {
}
