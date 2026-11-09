package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.vector;

import com.stefanzalan.ai_knowledge_base_assistant.document.application.port.ChunkVectorStore;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.port.VectorChunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter that writes chunks through Spring AI's {@link PgVectorStore}.
 * The Spring AI {@link Document} id is set to the chunk UUID so that the
 * {@code vector_store.id} foreign key points back to {@code document_chunk}.
 */
@Component
public class PgVectorChunkStore implements ChunkVectorStore {

    private final PgVectorStore pgVectorStore;

    public PgVectorChunkStore(PgVectorStore pgVectorStore) {
        this.pgVectorStore = pgVectorStore;
    }

    @Override
    public void addAll(List<VectorChunk> chunks) {
        List<Document> documents = chunks.stream()
                .map(chunk -> Document.builder()
                        .id(chunk.id().toString())
                        .text(chunk.content())
                        .metadata(chunk.metadata())
                        .build())
                .toList();
        pgVectorStore.add(documents);
    }
}
