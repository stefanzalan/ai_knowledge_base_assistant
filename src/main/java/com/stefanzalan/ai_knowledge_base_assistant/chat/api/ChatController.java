package com.stefanzalan.ai_knowledge_base_assistant.chat.api;

import com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto.AskRequest;
import com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto.AskResponse;
import com.stefanzalan.ai_knowledge_base_assistant.chat.api.dto.CitationResponse;
import com.stefanzalan.ai_knowledge_base_assistant.chat.application.ChatAnswer;
import com.stefanzalan.ai_knowledge_base_assistant.chat.application.ChatCitation;
import com.stefanzalan.ai_knowledge_base_assistant.chat.application.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Grounded question-answering endpoint. Answers are generated exclusively
 * from currently approved knowledge-base chunks; insufficient evidence is
 * reported explicitly instead of guessing.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        ChatAnswer answer = chatService.ask(request.question().trim(), request.product());

        List<CitationResponse> citations = answer.citations().stream()
                .map(citation -> new CitationResponse(
                        citation.documentId(),
                        citation.documentVersionId(),
                        citation.title(),
                        citation.sourceUri(),
                        citation.chunkIndex()))
                .toList();

        return new AskResponse(answer.answer(), answer.insufficientEvidence(), citations);
    }
}
