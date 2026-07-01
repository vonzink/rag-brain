package com.msfg.rag.controller;

import com.msfg.rag.dto.FederationDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/mcp/tools")
public class McpFacadeController {

    private final FederationController federation;

    public McpFacadeController(FederationController federation) {
        this.federation = federation;
    }

    @GetMapping
    public List<ToolDto> tools() {
        return List.of(
                tool("rag_brain_list_brains", "List active rag-brain brains", List.of()),
                tool("rag_brain_readiness", "Read public attach readiness for a brain", List.of("slug")),
                tool("rag_brain_ask", "Ask a public-safe question against a rag-brain brain", List.of("slug", "message")),
                tool("rag_brain_retrieve", "Retrieve public evidence chunks from a rag-brain brain", List.of("slug", "question")));
    }

    @PostMapping("/{toolName}")
    public Object call(@PathVariable String toolName,
                       @RequestHeader(value = "Authorization", required = false) String authorization,
                       @RequestHeader(value = "Host", required = false) String host,
                       @RequestHeader(value = "Origin", required = false) String origin,
                       @RequestBody Map<String, Object> input) {
        Map<String, Object> body = input == null ? Map.of() : input;
        return switch (toolName) {
            case "rag_brain_list_brains" -> federation.brains(authorization, host, origin);
            case "rag_brain_readiness" -> federation.readiness(require(body, "slug"), authorization, host, origin);
            case "rag_brain_ask" -> federation.ask(require(body, "slug"), authorization, host, origin,
                    new FederationDtos.FederationAskRequest(conversationId(body), sessionId(body),
                            require(body, "message"), string(body, "pageRoute"), facts(body)));
            case "rag_brain_retrieve" -> federation.retrieve(require(body, "slug"), authorization, host, origin,
                    new FederationDtos.FederationRetrieveRequest(require(body, "question")));
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown MCP tool: " + toolName);
        };
    }

    private static ToolDto tool(String name, String description, List<String> required) {
        Map<String, Object> properties = Map.of(
                "slug", Map.of("type", "string"),
                "message", Map.of("type", "string"),
                "question", Map.of("type", "string"),
                "sessionId", Map.of("type", "string"),
                "pageRoute", Map.of("type", "string"),
                "facts", Map.of("type", "object"));
        return new ToolDto(name, description, Map.of(
                "type", "object",
                "required", required,
                "properties", properties));
    }

    private static String require(Map<String, Object> input, String key) {
        String value = string(input, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String string(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String sessionId(Map<String, Object> input) {
        String value = string(input, "sessionId");
        return value == null || value.isBlank() ? "mcp-session" : value;
    }

    private static UUID conversationId(Map<String, Object> input) {
        String value = string(input, "conversationId");
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> facts(Map<String, Object> input) {
        Object value = input.get("facts");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public record ToolDto(String name, String description, Map<String, Object> inputSchema) {}
}
