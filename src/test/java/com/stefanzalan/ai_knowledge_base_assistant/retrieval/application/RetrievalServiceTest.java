package com.stefanzalan.ai_knowledge_base_assistant.retrieval.application;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private VectorStore vectorStore;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        RetrievalProperties properties = new RetrievalProperties();
        properties.setTopK(7);
        properties.setMinSimilarity(0.6);
        service = new RetrievalService(vectorStore, properties);
    }

    private Document documentWithMetadata(Map<String, Object> metadata) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text("chunk content")
                .metadata(metadata)
                .score(0.91)
                .build();
    }

    private Map<String, Object> validMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", UUID.randomUUID().toString());
        metadata.put("documentVersionId", UUID.randomUUID().toString());
        metadata.put("title", "Home Loan FAQ");
        metadata.put("sourceUri", "kb://docs/home-loan");
        metadata.put("chunkIndex", 3);
        metadata.put("product", "HOME_LOAN");
        metadata.put("retrievalEligible", true);
        return metadata;
    }

    @Test
    void mapsVectorStoreDocumentsToRetrievedChunks() {
        Map<String, Object> metadata = validMetadata();
        UUID documentId = UUID.fromString((String) metadata.get("documentId"));
        UUID versionId = UUID.fromString((String) metadata.get("documentVersionId"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(documentWithMetadata(metadata)));

        RetrievalResult result = service.search("What is the rate?", FinancialProduct.HOME_LOAN);

        assertThat(result.query()).isEqualTo("What is the rate?");
        assertThat(result.product()).isEqualTo(FinancialProduct.HOME_LOAN);
        assertThat(result.chunks()).hasSize(1);
        RetrievedChunk chunk = result.chunks().get(0);
        assertThat(chunk.content()).isEqualTo("chunk content");
        assertThat(chunk.documentId()).isEqualTo(documentId);
        assertThat(chunk.documentVersionId()).isEqualTo(versionId);
        assertThat(chunk.title()).isEqualTo("Home Loan FAQ");
        assertThat(chunk.sourceUri()).isEqualTo("kb://docs/home-loan");
        assertThat(chunk.chunkIndex()).isEqualTo(3);
        assertThat(chunk.similarityScore()).isEqualTo(0.91);
    }

    @Test
    void returnsEmptyResultWhenVectorStoreReturnsNothing() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RetrievalResult result = service.search("unrelated question", FinancialProduct.BANK_ACCOUNT);

        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void skipsDocumentsWithMissingMetadata() {
        Document noMetadata = Document.builder().id("x").text("orphan").build();
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(noMetadata, documentWithMetadata(validMetadata())));

        RetrievalResult result = service.search("q", FinancialProduct.HOME_LOAN);

        assertThat(result.chunks()).hasSize(1);
    }

    @Test
    void skipsDocumentsWithMalformedMetadata() {
        Map<String, Object> bad = validMetadata();
        bad.put("documentId", "not-a-uuid");
        bad.put("chunkIndex", "not-a-number");
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(documentWithMetadata(bad)));

        RetrievalResult result = service.search("q", FinancialProduct.HOME_LOAN);

        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void wrapsVectorStoreFailuresAsServiceUnavailable() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("Ollama connection refused"));

        assertThatThrownBy(() -> service.search("q", FinancialProduct.HOME_LOAN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void buildsSearchRequestWithConfiguredTopKThresholdAndProductFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service.search("eligibility requirements", FinancialProduct.PERSONAL_LOAN);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();

        assertThat(request.getQuery()).isEqualTo("eligibility requirements");
        assertThat(request.getTopK()).isEqualTo(7);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.6);
        assertThat(request.getFilterExpression()).isNotNull();
    }
}
