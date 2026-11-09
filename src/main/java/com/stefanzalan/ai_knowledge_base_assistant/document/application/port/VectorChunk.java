package com.stefanzalan.ai_knowledge_base_assistant.document.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * A chunk ready to be written to the vector store. The {@code id} must be
 * the UUID of the persisted {@code document_chunk} row: the vector store
 * uses it as primary key, which is also the foreign key back to the chunk.
 */
public record VectorChunk(UUID id, String content, Map<String, Object> metadata) {
}
