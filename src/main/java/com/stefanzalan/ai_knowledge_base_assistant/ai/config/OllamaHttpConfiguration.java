package com.stefanzalan.ai_knowledge_base_assistant.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Applies {@link AiProperties#getRequestTimeout()} to the HTTP client used
 * by the Spring AI Ollama integration. Spring AI's
 * {@code OllamaApiAutoConfiguration} picks up a {@link RestClient.Builder}
 * bean via {@code ObjectProvider}, so providing one here enforces the
 * configured timeout on chat and embedding calls without touching the
 * auto-configured {@code OllamaApi} bean.
 */
@Configuration
public class OllamaHttpConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Primary
    RestClient.Builder ollamaRestClientBuilder(AiProperties aiProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(aiProperties.getRequestTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }
}
