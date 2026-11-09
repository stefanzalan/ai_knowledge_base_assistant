package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionPreparationService {

    private final DocumentIngestionStore ingestionStore;

    public DocumentIngestionPreparationService(
            DocumentIngestionStore ingestionStore
    ) {
        this.ingestionStore = ingestionStore;
    }

    public IngestionPreparation prepareWithRetry(
            String sourceUri,
            FinancialProduct product,
            String title,
            String originalFilename,
            String checksum
    ) {
        try {
            return ingestionStore.prepare(
                    sourceUri, product, title, originalFilename, checksum
            );
        } catch (DataIntegrityViolationException firstFailure) {
            try {
                return ingestionStore.prepare(
                        sourceUri, product, title, originalFilename, checksum
                );
            } catch (DataIntegrityViolationException retryFailure) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "Concurrent ingestion conflict; please retry the request"
                );
            }
        }
    }
}