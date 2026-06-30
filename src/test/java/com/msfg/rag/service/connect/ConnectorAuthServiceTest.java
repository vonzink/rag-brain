package com.msfg.rag.service.connect;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.BrainConnectorClient;
import com.msfg.rag.domain.BrainConnectorEvent;
import com.msfg.rag.repository.BrainConnectorClientRepository;
import com.msfg.rag.repository.BrainConnectorEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorAuthServiceTest {

    private final BrainConnectorClientRepository clients = mock(BrainConnectorClientRepository.class);
    private final BrainConnectorEventRepository events = mock(BrainConnectorEventRepository.class);
    private final ConnectorAuthService service = new ConnectorAuthService(clients, events);

    @Test
    void rotateTokenStoresHashOnly() {
        UUID id = UUID.randomUUID();
        BrainConnectorClient client = new BrainConnectorClient(id, "Agent", "MCP_AGENT", null);
        when(clients.findById(id)).thenReturn(Optional.of(client));
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String token = service.rotateToken(id);

        assertTrue(token.startsWith("rb_conn_"));
        assertNotEquals(token, client.getTokenHash());
        assertEquals(64, client.getTokenHash().length());
        verify(clients).save(client);
    }

    @Test
    void requireRejectsMissingTokenAsUnauthorized() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require(null, ConnectorScope.ASK_PUBLIC, TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void requireRejectsInvalidTokenAsUnauthorizedAndRecordsFailure() {
        when(clients.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer rb_conn_bad", ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("AUTH_FAILURE", event.getValue().getEventType());
        assertEquals("401", event.getValue().getStatus());
    }

    @Test
    void requireRejectsMissingScopeAsForbidden() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.BRAINS_LIST));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void requireRejectsDisabledClientAsUnauthorized() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, false, List.of(ConnectorScope.ASK_PUBLIC));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                        TestBrains.DEFAULT_ID, "peer.local", "ASK"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void requireAcceptsBearerTokenUpdatesLastUsedAndRecordsEvent() {
        String token = "rb_conn_known";
        BrainConnectorClient client = client(token, true, List.of(ConnectorScope.ASK_PUBLIC));
        when(clients.findByTokenHash(ConnectorAuthService.hashToken(token))).thenReturn(Optional.of(client));
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BrainConnectorClient validated = service.require("Bearer " + token, ConnectorScope.ASK_PUBLIC,
                TestBrains.DEFAULT_ID, "peer.local", "ASK");

        assertEquals(client, validated);
        assertNotNull(client.getLastUsedAt());
        verify(clients).save(client);
        ArgumentCaptor<BrainConnectorEvent> event = ArgumentCaptor.forClass(BrainConnectorEvent.class);
        verify(events).save(event.capture());
        assertEquals("ASK", event.getValue().getEventType());
        assertEquals("200", event.getValue().getStatus());
    }

    private static BrainConnectorClient client(String token, boolean enabled, List<String> scopes) {
        BrainConnectorClient client = new BrainConnectorClient(
                UUID.randomUUID(), "Agent", "MCP_AGENT", ConnectorAuthService.hashToken(token));
        client.setBrainId(TestBrains.DEFAULT_ID);
        client.setScopes(scopes);
        client.setEnabled(enabled);
        return client;
    }
}
