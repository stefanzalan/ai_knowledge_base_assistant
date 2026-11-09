package com.stefanzalan.ai_knowledge_base_assistant.document.api;

import com.stefanzalan.ai_knowledge_base_assistant.document.api.dto.IngestionResponse;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionService;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionCommand;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Admin endpoint for submitting Markdown knowledge base documents.
 * Thin controller: validation of the payload shape happens here via
 * Bean Validation; all business rules live in the application service.
 */
@RestController
@RequestMapping("/api/v1/admin/documents")
@Validated
public class DocumentAdminController {

    private final DocumentIngestionService ingestionService;

    public DocumentAdminController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionResponse> ingest(
            @RequestPart("file") MultipartFile file,
            @RequestParam("title") @NotBlank @Size(max = 500) String title,
            @RequestParam("product") FinancialProduct product,
            @RequestParam("sourceUri") @NotBlank @Size(max = 1000) String sourceUri) {

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        IngestionCommand command = new IngestionCommand(
                title.trim(), product, sourceUri.trim(), file.getOriginalFilename(), content);

        IngestionResult result = ingestionService.ingest(command);

        HttpStatus status = result.alreadyIngested() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(IngestionResponse.from(result));
    }
}
