package com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/chat/ask}.
 */
public record AskRequest(
        @NotBlank @Size(max = 2000) String question,
        @NotNull FinancialProduct product) {
}
