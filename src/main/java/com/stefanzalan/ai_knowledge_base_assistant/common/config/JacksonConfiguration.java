package com.stefanzalan.ai_knowledge_base_assistant.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Spring-managed {@link ObjectMapper} used for parsing
 * structured model output. Spring Boot 4's web starter does not
 * auto-configure one in this project setup.
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
