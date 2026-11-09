package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.storage;

import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.config.IngestionProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores original Markdown files on the local filesystem, content-addressed
 * by their SHA-256 checksum ({@code <checksum>.md}). Identical content is
 * stored only once regardless of how many documents reference it.
 *
 * <p>The untrusted original filename is never used as a path component;
 * the checksum is a hex string, so path traversal is not possible. A
 * normalization guard is kept as defense in depth.</p>
 */
@Component
public class LocalDocumentFileStorage {

    private final Path storageDirectory;

    public LocalDocumentFileStorage(IngestionProperties properties) {
        this.storageDirectory = Path.of(properties.getStorageDirectory())
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Writes the content and returns the absolute path of the stored file.
     * The path is an internal detail and must not be exposed via the API.
     */
    public Path store(String checksum, byte[] content) {
        Path target = storageDirectory.resolve(checksum + ".md").normalize();
        if (!target.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Resolved storage path escapes the storage directory");
        }
        try {
            Files.createDirectories(storageDirectory);
            Files.write(target, content);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store document file", e);
        }
    }
}
