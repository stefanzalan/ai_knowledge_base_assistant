package com.stefanzalan.ai_knowledge_base_assistant.common.exception;

import java.time.Instant;

/**
 * Uniform error payload returned by the REST API.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path) {
}
