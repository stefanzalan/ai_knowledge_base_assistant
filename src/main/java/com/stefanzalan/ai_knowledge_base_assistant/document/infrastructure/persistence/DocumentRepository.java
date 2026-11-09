package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.persistence;

import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findBySourceUriAndProduct(String sourceUri, FinancialProduct product);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       SELECT d
       FROM DocumentEntity d
       WHERE d.sourceUri = :sourceUri
         AND d.product = :product
       """)
    Optional<DocumentEntity> findBySourceUriAndProductForUpdate(
            @Param("sourceUri") String sourceUri,
            @Param("product") FinancialProduct product
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       SELECT d
       FROM DocumentEntity d
       WHERE d.id = :id
       """)
    Optional<DocumentEntity> findByIdForUpdate(@Param("id") UUID id);
}
