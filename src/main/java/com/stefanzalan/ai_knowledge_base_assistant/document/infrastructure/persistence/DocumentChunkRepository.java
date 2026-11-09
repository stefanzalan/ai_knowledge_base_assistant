package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, UUID> {

    List<DocumentChunkEntity> findByDocumentVersionIdOrderByChunkIndex(UUID documentVersionId);
}
