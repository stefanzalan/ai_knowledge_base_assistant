package com.stefanzalan.ai_knowledge_base_assistant.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception for expected, client-visible application errors.
 * Carries the HTTP status that the {@link GlobalExceptionHandler} maps to.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
