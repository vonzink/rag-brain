package com.msfg.rag.controller;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.dto.FederationDtos;
import com.msfg.rag.dto.PublicAskResponse;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.connect.ConnectorAuthService;
import com.msfg.rag.service.connect.ConnectorQueryService;
import com.msfg.rag.service.connect.ConnectorScope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FederationControllerTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final ConnectorAuthService auth = mock(ConnectorAuthService.class);
    private final ConnectorQueryService query = mock(ConnectorQueryService.class);
    private final BrainReadinessController readiness = mock(BrainReadinessController.class);
    private final FederationController controller = new FederationController(brains, auth, query, readiness);

    @Test
    void askRequiresAskScopeAndDelegatesToConnectorQueryService() {
        Brain brain = brain();
        FederationDtos.FederationAskRequest req = new FederationDtos.FederationAskRequest(
                null, "s1", "What can you do?", "/", Map.of());
        PublicAskResponse response = new PublicAskResponse("ANSWER", "Answer", "Answer",
                null, List.of(), List.of(), List.of(), 0.91, null, UUID.randomUUID(),
                "disclaimer", false);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(query.ask(brain, req)).thenReturn(response);

        assertEquals(response, controller.ask("generic", "Bearer rb_conn_ok", "peer.local", req));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.ASK_PUBLIC,
                TestBrains.DEFAULT_ID, "peer.local", "ASK");
        verify(query).ask(brain, req);
    }

    @Test
    void missingAskScopePropagatesForbidden() {
        Brain brain = brain();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "scope missing"))
                .when(auth).require("Bearer rb_conn_ok", ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.ask("generic", "Bearer rb_conn_ok", "peer.local",
                        new FederationDtos.FederationAskRequest(null, "s1", "Q", "/", Map.of())));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void missingAuthorizationHeaderReturnsConnectorUnauthorized() throws Exception {
        Brain brain = brain();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token is required"))
                .when(auth).require(null, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK");
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new com.msfg.rag.exception.GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/connect/v1/brains/generic/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Host", "peer.local")
                        .content("""
                                {
                                  "sessionId": "s1",
                                  "message": "What can you do?",
                                  "pageRoute": "/",
                                  "facts": {}
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Connector token is required"));
    }

    @Test
    void retrieveRequiresRetrieveScopeAndDelegates() {
        Brain brain = brain();
        FederationDtos.FederationRetrieveRequest req = new FederationDtos.FederationRetrieveRequest("What is PMI?");
        FederationDtos.FederationRetrieveResponse response =
                new FederationDtos.FederationRetrieveResponse(List.of(), 0.0, false);
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(query.retrieve(brain, req)).thenReturn(response);

        assertEquals(response, controller.retrieve("generic", "Bearer rb_conn_ok", "peer.local", req));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.RETRIEVE_PUBLIC,
                TestBrains.DEFAULT_ID, "peer.local", "RETRIEVE");
        verify(query).retrieve(brain, req);
    }

    private static Brain brain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic Brain");
        brain.setActive(true);
        return brain;
    }
}
