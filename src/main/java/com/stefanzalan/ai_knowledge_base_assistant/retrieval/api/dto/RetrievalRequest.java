package com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/retrieval/search}.
 */
public record RetrievalRequest(
        @NotBlank @Size(max = 2000) String query,
        @NotNull FinancialProduct product) {
}
