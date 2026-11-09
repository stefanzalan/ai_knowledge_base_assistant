package com.stefanzalan.ai_knowledge_base_assistant.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void apiExceptionReturnsConfiguredStatusAndErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/test/api-exception"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("something went wrong"))
                .andExpect(jsonPath("$.path").value("/api/v1/test/api-exception"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @RestController
    static class TestController {

        @GetMapping("/api/v1/test/api-exception")
        String apiException() {
            throw new ApiException(HttpStatus.BAD_REQUEST, "something went wrong");
        }

        @GetMapping("/api/v1/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("sensitive internal detail");
        }
    }
}
