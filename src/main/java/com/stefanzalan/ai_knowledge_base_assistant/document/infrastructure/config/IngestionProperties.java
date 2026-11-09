package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Markdown document ingestion pipeline.
 */
@ConfigurationProperties(prefix = "app.ingestion")
@Validated
public class IngestionProperties {

    /**
     * Directory where original Markdown files are stored.
     * Must be outside the application source tree. Files are stored
     * content-addressed as {@code <sha256>.md}.
     */
    @NotBlank
    private String storageDirectory = "./data/documents";

    /**
     * Target maximum chunk size in characters. Chunks are packed at
     * heading/paragraph boundaries and only hard-split when a single
     * block exceeds this size.
     */
    @Min(100)
    private int chunkSize = 2000;

    /**
     * Number of characters carried over from the end of a chunk into the
     * beginning of the next chunk to preserve context across boundaries.
     */
    @Min(0)
    private int chunkOverlap = 200;

    public String getStorageDirectory() {
        return storageDirectory;
    }

    public void setStorageDirectory(String storageDirectory) {
        this.storageDirectory = storageDirectory;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}
