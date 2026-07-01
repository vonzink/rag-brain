package com.ragbrain.rag.controller;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.BrainConnectorClient;
import com.ragbrain.rag.dto.ConnectorClientRequest;
import com.ragbrain.rag.repository.BrainConnectorClientRepository;
import com.ragbrain.rag.repository.BrainConnectorEventRepository;
import com.ragbrain.rag.service.connect.ConnectorAuthService;
import com.ragbrain.rag.service.connect.ConnectorScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminConnectorControllerTest {

    private final BrainConnectorClientRepository clients = mock(BrainConnectorClientRepository.class);
    private final BrainConnectorEventRepository events = mock(BrainConnectorEventRepository.class);
    private final ConnectorAuthService auth = mock(ConnectorAuthService.class);
    private final AdminConnectorController controller =
            new AdminConnectorController(clients, events, auth);

    @Test
    void createConnectorWithoutToken() {
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConnectorClientRequest req = new ConnectorClientRequest(
                "Agent", "MCP_AGENT", TestBrains.DEFAULT_ID,
                List.of(ConnectorScope.ASK_PUBLIC), List.of(), List.of(), true);

        var dto = controller.create(req);

        assertEquals("Agent", dto.name());
        assertEquals("MCP_AGENT", dto.type());
        assertFalse(dto.hasToken());
        assertEquals(List.of(ConnectorScope.ASK_PUBLIC), dto.scopes());
    }

    @Test
    void createRejectsMissingRequestBody() {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.create(null));

        assertEquals("request body is required", ex.getMessage());
    }

    @Test
    void rotateTokenReturnsPlainTokenOnce() {
        UUID id = UUID.randomUUID();
        BrainConnectorClient client = new BrainConnectorClient(id, "Agent", "MCP_AGENT", "hash");
        when(clients.findById(id)).thenReturn(Optional.of(client));
        when(auth.rotateToken(id)).thenReturn("rb_conn_secret");

        var response = controller.rotateToken(id);

        assertEquals("rb_conn_secret", response.token());
        assertTrue(response.client().hasToken());
    }

    @Test
    void disableConnector() {
        UUID id = UUID.randomUUID();
        BrainConnectorClient client = new BrainConnectorClient(id, "Agent", "MCP_AGENT", "hash");
        client.setEnabled(true);
        when(clients.findById(id)).thenReturn(Optional.of(client));
        when(clients.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = controller.disable(id);

        assertFalse(dto.enabled());
    }
}
