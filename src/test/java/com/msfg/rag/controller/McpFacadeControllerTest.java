package com.msfg.rag.controller;

import com.msfg.rag.dto.FederationDtos;
import com.msfg.rag.dto.PublicAskResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpFacadeControllerTest {

    private final FederationController federation = mock(FederationController.class);
    private final McpFacadeController controller = new McpFacadeController(federation);

    @Test
    void toolListIncludesRagBrainTools() {
        List<McpFacadeController.ToolDto> tools = controller.tools();

        List<String> names = tools.stream().map(McpFacadeController.ToolDto::name).toList();
        assertTrue(names.contains("rag_brain_list_brains"));
        assertTrue(names.contains("rag_brain_readiness"));
        assertTrue(names.contains("rag_brain_ask"));
        assertTrue(names.contains("rag_brain_retrieve"));
    }

    @Test
    void askToolDelegatesToFederationAsk() {
        PublicAskResponse response = new PublicAskResponse("ANSWER", "Answer", "Answer",
                null, List.of(), List.of(), List.of(), 0.91, null, UUID.randomUUID(),
                "disclaimer", false);
        when(federation.ask(org.mockito.Mockito.eq("generic"), org.mockito.Mockito.eq("Bearer rb_conn_ok"),
                org.mockito.Mockito.eq("peer.local"), org.mockito.Mockito.eq("https://trusted.example.com"),
                org.mockito.Mockito.any()))
                .thenReturn(response);

        Object result = controller.call("rag_brain_ask", "Bearer rb_conn_ok",
                "peer.local", "https://trusted.example.com",
                Map.of("slug", "generic", "message", "What can you do?", "sessionId", "s1"));

        assertEquals(response, result);
        ArgumentCaptor<FederationDtos.FederationAskRequest> request =
                ArgumentCaptor.forClass(FederationDtos.FederationAskRequest.class);
        verify(federation).ask(org.mockito.Mockito.eq("generic"), org.mockito.Mockito.eq("Bearer rb_conn_ok"),
                org.mockito.Mockito.eq("peer.local"), org.mockito.Mockito.eq("https://trusted.example.com"),
                request.capture());
        assertEquals("What can you do?", request.getValue().message());
    }

    @Test
    void unknownToolReturnsNotFound() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.call("missing_tool", "Bearer rb_conn_ok",
                        "peer.local", "https://trusted.example.com", Map.of()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
