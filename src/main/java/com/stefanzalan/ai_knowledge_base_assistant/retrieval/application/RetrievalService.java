package com.stefanzalan.ai_knowledge_base_assistant.retrieval.application;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.config.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retrieval use case: embeds the question via the configured embedding
 * model (inside {@link VectorStore#similaritySearch}) and returns the
 * most relevant chunks that are both in the requested product and
 * flagged {@code retrievalEligible} in their vector metadata.
 *
 * <p>The product and eligibility predicates are part of the vector-store
 * filter expression, so ineligible vectors are excluded by the database
 * query itself — before top-K selection — never filtered afterwards.</p>
 *
 * <p>No database transaction is held while Ollama generates the query
 * embedding.</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final String METADATA_PRODUCT = "product";
    private static final String METADATA_RETRIEVAL_ELIGIBLE = "retrievalEligible";
    private static final String METADATA_DOCUMENT_ID = "documentId";
    private static final String METADATA_DOCUMENT_VERSION_ID = "documentVersionId";
    private static final String METADATA_TITLE = "title";
    private static final String METADATA_SOURCE_URI = "sourceUri";
    private static final String METADATA_CHUNK_INDEX = "chunkIndex";

    private final VectorStore vectorStore;
    private final RetrievalProperties properties;

    public RetrievalService(VectorStore vectorStore, RetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public RetrievalResult search(String query, FinancialProduct product) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                        builder.eq(METADATA_PRODUCT, product.name()),
                        builder.eq(METADATA_RETRIEVAL_ELIGIBLE, true))
                .build();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(properties.getTopK())
                .similarityThreshold(properties.getMinSimilarity())
                .filterExpression(filter)
                .build();

        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Retrieval failed: embedding or vector store unavailable", e);
        }

        List<RetrievedChunk> chunks = new ArrayList<>(documents.size());
        for (Document document : documents) {
            RetrievedChunk chunk = toRetrievedChunk(document);
            if (chunk != null) {
                chunks.add(chunk);
            }
        }
        return new RetrievalResult(query, product, chunks);
    }

    /**
     * Maps a Spring AI {@link Document} to a {@link RetrievedChunk}.
     * Returns {@code null} when required source metadata is missing or
     * malformed — such chunks are skipped rather than returned with
     * fabricated source information.
     */
    private RetrievedChunk toRetrievedChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            log.warn("Skipping retrieved document {} without metadata", document.getId());
            return null;
        }

        UUID documentId = parseUuid(metadata.get(METADATA_DOCUMENT_ID));
        UUID documentVersionId = parseUuid(metadata.get(METADATA_DOCUMENT_VERSION_ID));
        Integer chunkIndex = parseInteger(metadata.get(METADATA_CHUNK_INDEX));
        String title = asString(metadata.get(METADATA_TITLE));
        String sourceUri = asString(metadata.get(METADATA_SOURCE_URI));

        if (documentId == null || documentVersionId == null || chunkIndex == null
                || title == null || sourceUri == null) {
            log.warn("Skipping retrieved document {} with incomplete source metadata", document.getId());
            return null;
        }

        return new RetrievedChunk(
                document.getText(),
                documentId,
                documentVersionId,
                title,
                sourceUri,
                chunkIndex,
                document.getScore());
    }

    private UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
