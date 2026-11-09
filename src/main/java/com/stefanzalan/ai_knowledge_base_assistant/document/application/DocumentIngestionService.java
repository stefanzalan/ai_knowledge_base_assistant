package com.stefanzalan.ai_knowledge_base_assistant.document.application;

import com.stefanzalan.ai_knowledge_base_assistant.common.exception.ApiException;
import com.stefanzalan.ai_knowledge_base_assistant.document.domain.DocumentStatus;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.checksum.Sha256ChecksumCalculator;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.chunking.MarkdownChunker;
import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.storage.LocalDocumentFileStorage;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.port.ChunkVectorStore;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.port.VectorChunk;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the Markdown ingestion use case.
 *
 * <p>Transaction boundaries: database transactions are confined to
 * {@link DocumentIngestionStore} methods. File storage, Markdown chunking
 * and the embedding/vector-store call (which may invoke Ollama) all run
 * outside of any transaction. If anything fails after the version was
 * prepared, the version and its partial data are marked FAILED and cleaned
 * up via {@link DocumentIngestionStore#markFailed}.</p>
 */
@Service
public class DocumentIngestionService {

    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentIngestionPreparationService preparationService;
    private final DocumentIngestionStore ingestionStore;
    private final Sha256ChecksumCalculator checksumCalculator;
    private final LocalDocumentFileStorage fileStorage;
    private final MarkdownChunker chunker;
    private final ChunkVectorStore chunkVectorStore;

    public DocumentIngestionService(DocumentIngestionPreparationService preparationService,
                                    DocumentIngestionStore ingestionStore,
                                    Sha256ChecksumCalculator checksumCalculator,
                                    LocalDocumentFileStorage fileStorage,
                                    MarkdownChunker chunker,
                                    ChunkVectorStore chunkVectorStore) {
        this.preparationService = preparationService;
        this.ingestionStore = ingestionStore;
        this.checksumCalculator = checksumCalculator;
        this.fileStorage = fileStorage;
        this.chunker = chunker;
        this.chunkVectorStore = chunkVectorStore;
    }

    public IngestionResult ingest(IngestionCommand command) {
        validate(command);

        String markdown = decodeUtf8(command.content());
        String checksum = checksumCalculator.sha256Hex(command.content());

        IngestionPreparation preparation = preparationService.prepareWithRetry(
                command.sourceUri(),
                command.product(),
                command.title(),
                command.originalFilename(),
                checksum
        );

        if (preparation.alreadyIngested()) {
            return new IngestionResult(
                    preparation.documentId(), preparation.versionId(), preparation.versionNumber(),
                    preparation.versionStatus(), 0, true);
        }

        UUID versionId = preparation.versionId();
        try {
            fileStorage.store(checksum, command.content());

            List<String> chunkTexts = chunker.chunk(markdown);
            if (chunkTexts.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Document produced no chunks");
            }

            List<ChunkContent> chunkContents = new ArrayList<>(chunkTexts.size());
            for (int i = 0; i < chunkTexts.size(); i++) {
                chunkContents.add(new ChunkContent(i, chunkTexts.get(i)));
            }

            List<PersistedChunk> persistedChunks = ingestionStore.persistChunks(versionId, chunkContents);

            List<VectorChunk> vectorChunks = new ArrayList<>(persistedChunks.size());
            for (PersistedChunk chunk : persistedChunks) {
                vectorChunks.add(new VectorChunk(chunk.id(), chunk.content(), buildMetadata(preparation, chunk)));
            }

            try {
                chunkVectorStore.addAll(vectorChunks);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Embedding/vector storage failed: " + e.getMessage(), e);
            }

            ingestionStore.markIngested(versionId);

            return new IngestionResult(
                    preparation.documentId(), versionId, preparation.versionNumber(),
                    DocumentStatus.PENDING_APPROVAL,
                    persistedChunks.size(), false);
        } catch (ApiException e) {
            ingestionStore.markFailed(versionId);
            throw e;
        } catch (Exception e) {
            ingestionStore.markFailed(versionId);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Document ingestion failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildMetadata(IngestionPreparation preparation, PersistedChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", preparation.documentId().toString());
        metadata.put("documentVersionId", preparation.versionId().toString());
        metadata.put("product", preparation.product().name());
        metadata.put("chunkIndex", chunk.index());
        metadata.put("sourceUri", preparation.sourceUri());
        metadata.put("title", preparation.title());
        // New vectors are never retrievable until their version is
        // explicitly activated; activateVersion() flips this flag.
        metadata.put("retrievalEligible", false);
        return metadata;
    }

    private void validate(IngestionCommand command) {
        if (command.content() == null || command.content().length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        String filename = command.originalFilename();
        if (filename == null || filename.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Original filename is required");
        }
        if (filename.length() > MAX_FILENAME_LENGTH
                || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid original filename");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only Markdown files (.md, .markdown) are supported");
        }
        if (command.title() == null || command.title().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Title is required");
        }
        if (command.sourceUri() == null || command.sourceUri().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Source URI is required");
        }
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File is not valid UTF-8 text");
        }
    }
}
