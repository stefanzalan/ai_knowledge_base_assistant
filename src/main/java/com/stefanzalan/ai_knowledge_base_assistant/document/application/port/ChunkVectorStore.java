package com.stefanzalan.ai_knowledge_base_assistant.document.application.port;

import java.util.List;

/**
 * Port for writing document chunks (with their embeddings) into the
 * vector store. Implemented by the Spring AI PGVectorStore adapter;
 * keeps the application layer free of Spring AI types.
 */
public interface ChunkVectorStore {

    /**
     * Embeds and persists the given chunks. Implementations may call an
     * external embedding provider; callers must not hold a database
     * transaction open while invoking this method.
     */
    void addAll(List<VectorChunk> chunks);
}
