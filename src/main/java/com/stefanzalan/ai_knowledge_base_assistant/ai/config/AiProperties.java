package com.stefanzalan.ai_knowledge_base_assistant.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application-level AI settings that are not covered by the
 * {@code spring.ai.*} auto-configuration namespace.
 *
 * <p>Model names, base URL and provider-specific options live under
 * {@code spring.ai.ollama.*} in {@code application.yml}.</p>
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /**
     * Maximum time allowed for a single AI provider request
     * (chat completion or embedding call).
     */
    private Duration requestTimeout = Duration.ofSeconds(120);

    /**
     * Dimensionality of vectors produced by the embedding model.
     * Must match the embedding column definition in the database
     * ({@code bge-m3} produces 1024-dimensional vectors).
     */
    private int embeddingDimensions = 1024;

    /**
     * Maximum total characters of retrieved chunk content sent to the chat
     * model per question. Chunks are included whole until the budget is
     * reached; oversized chunks are skipped, never partially truncated.
     */
    private int maxContextChars = 4000;

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }
}
