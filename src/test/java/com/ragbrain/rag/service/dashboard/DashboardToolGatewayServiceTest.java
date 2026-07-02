package com.ragbrain.rag.service.dashboard;

import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardToolGatewayServiceTest {

    private final DashboardToolGatewayService gateway =
            new DashboardToolGatewayService(new DashboardToolRegistry());

    @Test
    void readToolReturnsStubbedUntilDashboardAdapterIsConfigured() {
        var response = gateway.call("searchLoans", request(
                List.of("dashboard.loans.read"), Map.of("query", "Smith"), false, null));

        assertEquals(DashboardToolStatus.STUBBED, response.status());
        assertEquals("searchLoans", response.toolName());
        assertFalse(response.confirmationRequired());
        assertEquals("Live dashboard API adapter is not configured yet.", response.message());
        assertEquals("Smith", response.data().get("query"));
    }

    @Test
    void toolCallRejectsMissingUserPermission() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gateway.call("searchLoans", request(List.of(), Map.of("query", "Smith"), false, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void writeToolRequiresConfirmationBeforeExecution() {
        var response = gateway.call("createTask", request(
                List.of("dashboard.tasks.write"), Map.of("title", "Call borrower"), false, null));

        assertEquals(DashboardToolStatus.CONFIRMATION_REQUIRED, response.status());
        assertTrue(response.confirmationRequired());
        assertNotNull(response.confirmationId());
        assertTrue(response.message().contains("requires confirmation"));
    }

    @Test
    void confirmedWriteToolReturnsStubbedUntilDashboardAdapterIsConfigured() {
        var response = gateway.call("createTask", request(
                List.of("dashboard.tasks.write"), Map.of("title", "Call borrower"), true, "confirm-123"));

        assertEquals(DashboardToolStatus.STUBBED, response.status());
        assertFalse(response.confirmationRequired());
        assertEquals("Confirmed write accepted, but live dashboard API adapter is not configured yet.",
                response.message());
    }

    @Test
    void navigationToolReturnsSiteContainedRoute() {
        var response = gateway.call("navigateToRoute", request(
                List.of("dashboard.navigation.use"), Map.of("route", "/loans"), false, null));

        assertEquals(DashboardToolStatus.SUCCEEDED, response.status());
        assertEquals(List.of("/loans"), response.navigationHints());
        assertEquals("/loans", response.data().get("route"));
    }

    private static DashboardToolCallRequest request(List<String> permissions,
                                                    Map<String, Object> arguments,
                                                    boolean confirmed,
                                                    String confirmationId) {
        return new DashboardToolCallRequest("s1",
                new UserContext("user-1", "tenant-1", List.of("loan-officer"), permissions),
                arguments, confirmed, confirmationId);
    }
}
