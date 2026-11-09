package com.stefanzalan.ai_knowledge_base_assistant.document.domain;

/**
 * Lifecycle status shared by documents and document versions.
 *
 * <p>Mirrored by CHECK constraints in the database schema
 * (V2__create_knowledge_base_schema.sql). Keep both in sync.</p>
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    PENDING_APPROVAL,
    ACTIVE,
    FAILED,
    ARCHIVED
}
