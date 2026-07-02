package com.ragbrain.rag.controller;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.dto.AskResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardAskResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.UserContext;
import com.ragbrain.rag.repository.BrainRepository;
import com.ragbrain.rag.service.connect.ConnectorAuthService;
import com.ragbrain.rag.service.connect.ConnectorScope;
import com.ragbrain.rag.service.dashboard.DashboardAgentService;
import com.ragbrain.rag.service.dashboard.DashboardToolGatewayService;
import com.ragbrain.rag.service.dashboard.DashboardToolMode;
import com.ragbrain.rag.service.dashboard.DashboardToolRegistry;
import com.ragbrain.rag.service.dashboard.DashboardToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardAgentControllerTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final ConnectorAuthService auth = mock(ConnectorAuthService.class);
    private final DashboardToolRegistry registry = mock(DashboardToolRegistry.class);
    private final DashboardToolGatewayService gateway = mock(DashboardToolGatewayService.class);
    private final DashboardAgentService agent = mock(DashboardAgentService.class);
    private final DashboardAgentController controller =
            new DashboardAgentController(brains, auth, registry, gateway, agent);

    @Test
    void toolsRequiresDashboardToolsListScope() {
        Brain brain = brain();
        when(brains.findBySlug("dashboard-brain")).thenReturn(Optional.of(brain));
        when(registry.list()).thenReturn(List.of());

        assertEquals(List.of(), controller.tools("dashboard-brain", "Bearer rb_conn_ok",
                "dashboard.local", "https://dashboard.example.com"));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.DASHBOARD_TOOLS_LIST,
                TestBrains.DEFAULT_ID, "dashboard.local", "https://dashboard.example.com", "DASHBOARD_TOOLS_LIST");
    }

    @Test
    void readToolCallRequiresDashboardReadScope() {
        Brain brain = brain();
        var tool = new com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolDefinition(
                "searchLoans", "Search", DashboardToolMode.READ, false,
                List.of("dashboard.loans.read"), Map.of());
        var req = request(false);
        var response = response("searchLoans", DashboardToolMode.READ);
        when(brains.findBySlug("dashboard-brain")).thenReturn(Optional.of(brain));
        when(registry.require("searchLoans")).thenReturn(tool);
        when(gateway.call("searchLoans", req)).thenReturn(response);

        assertEquals(response, controller.callTool("dashboard-brain", "searchLoans",
                "Bearer rb_conn_ok", "dashboard.local", null, req));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.DASHBOARD_TOOLS_READ,
                TestBrains.DEFAULT_ID, "dashboard.local", null, "DASHBOARD_TOOL_READ");
    }

    @Test
    void writeToolCallRequiresDashboardWriteScope() {
        Brain brain = brain();
        var tool = new com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolDefinition(
                "createTask", "Create", DashboardToolMode.WRITE, true,
                List.of("dashboard.tasks.write"), Map.of());
        var req = request(true);
        var response = response("createTask", DashboardToolMode.WRITE);
        when(brains.findBySlug("dashboard-brain")).thenReturn(Optional.of(brain));
        when(registry.require("createTask")).thenReturn(tool);
        when(gateway.call("createTask", req)).thenReturn(response);

        assertEquals(response, controller.callTool("dashboard-brain", "createTask",
                "Bearer rb_conn_ok", "dashboard.local", null, req));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.DASHBOARD_TOOLS_WRITE,
                TestBrains.DEFAULT_ID, "dashboard.local", null, "DASHBOARD_TOOL_WRITE");
    }

    @Test
    void askRequiresDashboardAskScope() {
        Brain brain = brain();
        DashboardAskRequest req = new DashboardAskRequest(null, "s1", "What next?",
                "/dashboard", null, new UserContext("u1", "t1", List.of(), List.of()), Map.of());
        AskResponse answer = new AskResponse(UUID.randomUUID(), "Answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        DashboardAskResponse response = new DashboardAskResponse(answer, List.of());
        when(brains.findBySlug("dashboard-brain")).thenReturn(Optional.of(brain));
        when(agent.ask(brain, req)).thenReturn(response);

        assertEquals(response, controller.ask("dashboard-brain",
                "Bearer rb_conn_ok", "dashboard.local", null, req));

        verify(auth).require("Bearer rb_conn_ok", ConnectorScope.DASHBOARD_ASK,
                TestBrains.DEFAULT_ID, "dashboard.local", null, "DASHBOARD_ASK");
    }

    private static Brain brain() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        brain.setActive(true);
        return brain;
    }

    private static DashboardToolCallRequest request(boolean confirmed) {
        return new DashboardToolCallRequest("s1",
                new UserContext("u1", "t1", List.of(), List.of()),
                Map.of(), confirmed, null);
    }

    private static DashboardToolCallResponse response(String toolName, DashboardToolMode mode) {
        return new DashboardToolCallResponse(DashboardToolStatus.STUBBED, toolName, mode,
                "stub", false, null, Map.of(), List.of());
    }
}
