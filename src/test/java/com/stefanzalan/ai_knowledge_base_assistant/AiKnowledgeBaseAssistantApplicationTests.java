package com.stefanzalan.ai_knowledge_base_assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the full application context starts, Flyway migrations
 * apply cleanly and JPA validation passes against a real PostgreSQL
 * instance with the pgvector extension.
 */
@SpringBootTest
@Testcontainers
class AiKnowledgeBaseAssistantApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_test")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        // The context must start without a reachable Ollama instance;
        // model beans are only contacted when actually invoked.
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
    }

    @Test
    void contextLoads() {
    }

}
