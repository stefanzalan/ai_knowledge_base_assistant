package com.stefanzalan.ai_knowledge_base_assistant.retrieval.application;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;

import java.util.List;

/**
 * Outcome of a retrieval search: the original query, the product scope
 * and the eligible chunks found (possibly empty).
 */
public record RetrievalResult(
        String query,
        FinancialProduct product,
        List<RetrievedChunk> chunks) {
}
