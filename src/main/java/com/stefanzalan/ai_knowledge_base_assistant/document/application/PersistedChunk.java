package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import java.util.UUID;

/**
 * A persisted chunk with its database identity, ready to be written to
 * the vector store.
 */
public record PersistedChunk(UUID id, int index, String content) {
}
