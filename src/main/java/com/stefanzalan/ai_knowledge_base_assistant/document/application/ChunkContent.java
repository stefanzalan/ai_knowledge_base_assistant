package com.stefanzalan.ai_knowledge_base_assistant.document.application;

/**
 * A chunk of text to persist, before it has a database identity.
 */
public record ChunkContent(int index, String content) {
}
