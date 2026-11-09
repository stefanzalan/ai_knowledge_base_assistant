package com.stefanzalan.ai_knowledge_base_assistant.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the retrieval (RAG) pipeline.
 * Consumed by the retrieval application services in a later phase.
 */
@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalProperties {

    /** Maximum number of document chunks returned per query. */
    private int topK = 5;

    /**
     * Minimum cosine similarity a chunk must reach to be considered
     * relevant. Chunks below this threshold are discarded; if nothing
     * remains, the assistant abstains.
     */
    private double minSimilarity = 0.75;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }
}
