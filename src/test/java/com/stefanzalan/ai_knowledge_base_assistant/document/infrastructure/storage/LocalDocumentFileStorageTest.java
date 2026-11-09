package com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.storage;

import com.stefanzalan.ai_knowledge_base_assistant.document.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalDocumentFileStorage storage() {
        IngestionProperties properties = new IngestionProperties();
        properties.setStorageDirectory(tempDir.toString());
        return new LocalDocumentFileStorage(properties);
    }

    @Test
    void storesContentAddressedByChecksum() throws IOException {
        LocalDocumentFileStorage storage = storage();
        byte[] content = "# Hello".getBytes();

        Path path = storage.store("abc123", content);

        assertThat(path).isEqualTo(tempDir.resolve("abc123.md"));
        assertThat(Files.readAllBytes(path)).isEqualTo(content);
    }

    @Test
    void identicalContentIsStoredOnce() throws IOException {
        LocalDocumentFileStorage storage = storage();
        byte[] content = "same".getBytes();

        Path first = storage.store("deadbeef", content);
        Path second = storage.store("deadbeef", content);

        assertThat(first).isEqualTo(second);
        assertThat(Files.readAllBytes(first)).isEqualTo(content);
    }

    @Test
    void rejectsPathTraversalChecksum() {
        LocalDocumentFileStorage storage = storage();

        assertThatThrownBy(() -> storage.store("../escape", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
