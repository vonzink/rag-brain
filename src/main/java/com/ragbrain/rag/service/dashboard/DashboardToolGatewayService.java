package com.ragbrain.rag.service.dashboard;

import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.ragbrain.rag.dto.DashboardAgentDtos.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class DashboardToolGatewayService {

    private final DashboardToolRegistry registry;

    public DashboardToolGatewayService(DashboardToolRegistry registry) {
        this.registry = registry;
    }

    public DashboardToolCallResponse call(String toolName, DashboardToolCallRequest request) {
        DashboardToolDefinition tool = registry.require(toolName);
        requirePermissions(tool, request == null ? null : request.user());
        Map<String, Object> args = request == null || request.arguments() == null
                ? Map.of()
                : request.arguments();
        boolean confirmed = request != null && request.confirmed();
        String sessionId = request == null ? null : request.sessionId();

        if (tool.mode() == DashboardToolMode.WRITE && !confirmed) {
            String confirmationId = confirmationId(toolName, sessionId, args);
            return response(DashboardToolStatus.CONFIRMATION_REQUIRED, tool,
                    "Tool '" + toolName + "' requires confirmation before any dashboard write action.",
                    true, confirmationId, args, List.of());
        }

        if (tool.mode() == DashboardToolMode.NAVIGATE) {
            String route = route(args);
            return response(DashboardToolStatus.SUCCEEDED, tool,
                    "Navigation route prepared.", false, null,
                    route == null ? Map.of() : Map.of("route", route),
                    route == null ? List.of() : List.of(route));
        }

        String message = tool.mode() == DashboardToolMode.WRITE
                ? "Confirmed write accepted, but live dashboard API adapter is not configured yet."
                : "Live dashboard API adapter is not configured yet.";
        return response(DashboardToolStatus.STUBBED, tool, message,
                false, null, args, List.of());
    }

    private static void requirePermissions(DashboardToolDefinition tool, UserContext user) {
        List<String> permissions = user == null || user.permissions() == null
                ? List.of()
                : user.permissions();
        for (String required : tool.requiredPermissions()) {
            if (!permissions.contains(required)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Dashboard permission is required: " + required);
            }
        }
    }

    private static DashboardToolCallResponse response(DashboardToolStatus status,
                                                      DashboardToolDefinition tool,
                                                      String message,
                                                      boolean confirmationRequired,
                                                      String confirmationId,
                                                      Map<String, Object> data,
                                                      List<String> navigationHints) {
        return new DashboardToolCallResponse(status, tool.name(), tool.mode(), message,
                confirmationRequired, confirmationId, data, navigationHints);
    }

    private static String route(Map<String, Object> args) {
        Object value = args.get("route");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String route = String.valueOf(value).strip();
        return route.startsWith("/") ? route : "/" + route;
    }

    private static String confirmationId(String toolName, String sessionId, Map<String, Object> args) {
        try {
            String seed = toolName + "|" + (sessionId == null ? "" : sessionId) + "|" + args;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "confirm_" + HexFormat.of()
                    .formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
