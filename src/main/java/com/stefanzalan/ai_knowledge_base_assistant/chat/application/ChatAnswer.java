package com.stefanzalan.ai_knowledge_base_assistant.chat.application;

import java.util.List;

/**
 * Outcome of a grounded question: the answer text, whether the available
 * documentation was insufficient, and the validated citations.
 */
public record ChatAnswer(
        String answer,
        boolean insufficientEvidence,
        List<ChatCitation> citations) {
}
