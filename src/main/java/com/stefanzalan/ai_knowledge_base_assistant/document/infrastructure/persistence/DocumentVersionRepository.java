package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {

    List<DocumentVersionEntity> findByDocumentIdOrderByVersionNumber(UUID documentId);

    Optional<DocumentVersionEntity> findByDocumentIdAndCurrentTrue(UUID documentId);

    Optional<DocumentVersionEntity> findByDocumentIdAndSourceChecksum(UUID documentId, String sourceChecksum);

    Optional<DocumentVersionEntity> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    @Modifying(flushAutomatically = true)
    @Query("""
       UPDATE DocumentVersionEntity v
       SET v.current = false
       WHERE v.document.id = :documentId
         AND v.id <> :versionId
       """)
    int clearCurrentExcept(
            @Param("documentId") UUID documentId,
            @Param("versionId") UUID versionId
    );
}
