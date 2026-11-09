package com.stefanzalan.ai_knowledge_base_assistant.chat;

import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionService;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.DocumentIngestionStore;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionCommand;
import com.stefanzalan.ai_knowledge_base_assistant.document.application.IngestionResult;
import com.stefanzalan.ai_knowledge_base_assistant.product.domain.FinancialProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end chat tests against real PostgreSQL + pgvector retrieval.
 * Both AI models are mocked — no Ollama instance is required. The real
 * {@code RetrievalService} and {@code PgVectorStore} run, so grounding,
 * citation validation and insufficient-evidence behavior are exercised
 * against actual retrieved chunks.
 */
@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
class ChatApiTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("knowledge_base_test")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
        registry.add("app.ingestion.storage-directory", () -> createTempDir().toString());
        registry.add("app.ingestion.chunk-size", () -> "300");
        registry.add("app.ingestion.chunk-overlap", () -> "50");
        registry.add("app.retrieval.top-k", () -> "200");
        // All mocked embeddings are identical, so retrieval order is arbitrary;
        // a generous context budget keeps every activated chunk in context.
        registry.add("app.ai.max-context-chars", () -> "12000");
    }

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentIngestionStore ingestionStore;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void stubEmbeddings() {
        when(embeddingModel.embed(anyList(), any(), any())).thenAnswer(invocation -> {
            List<Document> documents = invocation.getArgument(0);
            return documents.stream().map(d -> embedding()).toList();
        });
        when(embeddingModel.embed(anyString())).thenReturn(embedding());
    }

    private static float[] embedding() {
        float[] vector = new float[1024];
        Arrays.fill(vector, 0.01f);
        return vector;
    }

    private static java.nio.file.Path createTempDir() {
        try {
            return java.nio.file.Files.createTempDirectory("kb-chat-test");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private IngestionResult ingestAndActivate(String sourceUri, FinancialProduct product, String content) {
        IngestionResult result = ingestionService.ingest(new IngestionCommand(
                "Doc " + sourceUri, product, sourceUri, "doc.md",
                content.getBytes(StandardCharsets.UTF_8)));
        ingestionStore.activateVersion(result.documentVersionId());
        return result;
    }

    private static String markdown(String heading, int paragraphs) {
        StringBuilder sb = new StringBuilder("# ").append(heading).append("\n\n");
        for (int i = 1; i <= paragraphs; i++) {
            sb.append("Paragraph ").append(i).append(" about ").append(heading)
                    .append(" with enough text to produce multiple chunks.\n\n");
        }
        return sb.toString();
    }

    private void modelReturnsJson(String json) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
    }

    private JsonNode ask(String question, FinancialProduct product) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + question + "\",\"product\":\"" + product.name() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(mvcResult.getResponse().getContentAsString());
    }

    @Test
    void validQuestionReturnsGroundedAnswerWithCitations() throws Exception {
        IngestionResult doc = ingestAndActivate(
                "kb://chat/home-loan", FinancialProduct.HOME_LOAN, markdown("Home Loan", 5));
        String sourceId = "source_" + doc.documentVersionId() + "_0";
        modelReturnsJson("""
                {"answer":"Home loans require an eligibility check.","insufficientEvidence":false,
                 "citationIds":["%s","source_bogus_1"]}
                """.formatted(sourceId));

        JsonNode response = ask("What are home loan requirements?", FinancialProduct.HOME_LOAN);

        assertThat(response.get("answer").asText()).isEqualTo("Home loans require an eligibility check.");
        assertThat(response.get("insufficientEvidence").asBoolean()).isFalse();
        assertThat(response.get("citations")).hasSize(1);
        JsonNode citation = response.get("citations").get(0);
        assertThat(citation.get("documentId").asText()).isEqualTo(doc.documentId().toString());
        assertThat(citation.get("documentVersionId").asText()).isEqualTo(doc.documentVersionId().toString());
        assertThat(citation.get("title").asText()).isEqualTo("Doc kb://chat/home-loan");
        assertThat(citation.get("sourceUri").asText()).isEqualTo("kb://chat/home-loan");
        assertThat(citation.get("chunkIndex").asInt()).isEqualTo(0);
        // Unknown citation identifier never reaches the response.
        assertThat(response.toString()).doesNotContain("bogus");
    }

    @Test
    void emptyRetrievalReturnsInsufficientEvidenceWithoutCallingModel() throws Exception {
        // PERSONAL_LOAN is never activated in this test class.
        clearInvocations(chatModel);

        JsonNode response = ask("anything at all", FinancialProduct.PERSONAL_LOAN);

        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        assertThat(response.get("citations")).isEmpty();
        assertThat(response.get("answer").asText()).isNotBlank();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void malformedModelOutputReturnsInsufficientEvidence() throws Exception {
        ingestAndActivate("kb://chat/malformed", FinancialProduct.HOME_LOAN, markdown("Malformed", 4));
        modelReturnsJson("definitely not json");

        JsonNode response = ask("malformed question", FinancialProduct.HOME_LOAN);

        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        assertThat(response.get("citations")).isEmpty();
    }

    @Test
    void answerWithoutValidCitationsIsDowngraded() throws Exception {
        ingestAndActivate("kb://chat/uncited", FinancialProduct.HOME_LOAN, markdown("Uncited", 4));
        modelReturnsJson("""
                {"answer":"The rate is 42%.","insufficientEvidence":false,"citationIds":["source_fake_1"]}
                """);

        JsonNode response = ask("uncited question", FinancialProduct.HOME_LOAN);

        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        assertThat(response.get("citations")).isEmpty();
        assertThat(response.get("answer").asText()).doesNotContain("42%");
    }

    @Test
    void modelInsufficientEvidenceIsPropagated() throws Exception {
        ingestAndActivate("kb://chat/insufficient", FinancialProduct.HOME_LOAN, markdown("Insufficient", 4));
        modelReturnsJson("""
                {"answer":"The documentation does not cover this topic.","insufficientEvidence":true,"citationIds":[]}
                """);

        JsonNode response = ask("uncovered topic", FinancialProduct.HOME_LOAN);

        assertThat(response.get("insufficientEvidence").asBoolean()).isTrue();
        // Model-generated text is never returned under the insufficient flag.
        assertThat(response.get("answer").asText()).isEqualTo(
                "The available documentation does not contain enough information to answer this question.");
        assertThat(response.get("citations")).isEmpty();
    }

    @Test
    void chatModelFailureReturns503() throws Exception {
        ingestAndActivate("kb://chat/failure", FinancialProduct.HOME_LOAN, markdown("Failure", 4));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Ollama unavailable"));

        mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"failure question\",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingProduct() throws Exception {
        mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"valid question\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooLongQuestion() throws Exception {
        String longQuestion = "x".repeat(2001);
        mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + longQuestion + "\",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseShapeMatchesContract() throws Exception {
        IngestionResult doc = ingestAndActivate(
                "kb://chat/shape", FinancialProduct.HOME_LOAN, markdown("Shape", 4));
        modelReturnsJson("""
                {"answer":"Shape answer.","insufficientEvidence":false,"citationIds":["source_%s_0"]}
                """.formatted(doc.documentVersionId()));

        mockMvc.perform(post("/api/v1/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"shape question\",\"product\":\"HOME_LOAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isString())
                .andExpect(jsonPath("$.insufficientEvidence").isBoolean())
                .andExpect(jsonPath("$.citations[0].documentId").isString())
                .andExpect(jsonPath("$.citations[0].documentVersionId").isString())
                .andExpect(jsonPath("$.citations[0].title").isString())
                .andExpect(jsonPath("$.citations[0].sourceUri").isString())
                .andExpect(jsonPath("$.citations[0].chunkIndex").isNumber());
    }
}
