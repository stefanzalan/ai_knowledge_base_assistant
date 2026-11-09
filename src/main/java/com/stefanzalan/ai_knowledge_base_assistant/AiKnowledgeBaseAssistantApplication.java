package com.stefanzalan.ai_knowledge_base_assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiKnowledgeBaseAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiKnowledgeBaseAssistantApplication.class, args);
    }

}
