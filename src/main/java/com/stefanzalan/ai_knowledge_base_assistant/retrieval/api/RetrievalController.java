package com.stefanzalan.ai_knowledge_base_assistant.retrieval.api;

import com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto.RetrievalRequest;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto.RetrievalResponse;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.api.dto.RetrievedChunkResponse;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.application.RetrievalResult;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.application.RetrievalService;
import com.stefanzalan.ai_knowledge_base_assistant.retrieval.application.RetrievedChunk;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Retrieval endpoint: returns relevant knowledge base chunks for a
 * question scoped to a financial product. Retrieval only — no answer
 * generation happens here.
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public RetrievalResponse search(@Valid @RequestBody RetrievalRequest request) {
        RetrievalResult result = retrievalService.search(request.query().trim(), request.product());

        List<RetrievedChunkResponse> chunks = result.chunks().stream()
                .map(chunk -> new RetrievedChunkResponse(
                        chunk.content(),
                        chunk.documentId(),
                        chunk.documentVersionId(),
                        chunk.title(),
                        chunk.sourceUri(),
                        chunk.chunkIndex(),
                        chunk.similarityScore()))
                .toList();

        return new RetrievalResponse(result.query(), result.product(), chunks);
    }
}
