package com.ragbrain.rag.service.dashboard;

import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.domain.SourceVisibility;
import com.ragbrain.rag.dto.AskRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardAskResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.UserContext;
import com.ragbrain.rag.service.AskService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardAgentService {

    private final AskService askService;
    private final DashboardToolRegistry registry;

    public DashboardAgentService(AskService askService, DashboardToolRegistry registry) {
        this.askService = askService;
        this.registry = registry;
    }

    public DashboardAskResponse ask(Brain brain, DashboardAskRequest req) {
        SourceVisibility visibility = visibility(req == null ? null : req.visibility());
        String sessionId = req == null || req.sessionId() == null || req.sessionId().isBlank()
                ? "dashboard-session"
                : req.sessionId();
        AskRequest askRequest = new AskRequest(
                req == null ? null : req.conversationId(),
                sessionId,
                req == null ? null : req.message(),
                null,
                null,
                req == null ? null : req.pageRoute(),
                "INTERNAL",
                facts(req == null ? null : req.facts(), req == null ? null : req.user()));
        return new DashboardAskResponse(
                askService.ask(askRequest, brain.getId(), visibility),
                registry.list());
    }

    private static SourceVisibility visibility(String value) {
        if (value == null || value.isBlank()) {
            return SourceVisibility.INTERNAL;
        }
        SourceVisibility visibility = SourceVisibility.valueOf(value.strip().toUpperCase(java.util.Locale.US));
        if (visibility == SourceVisibility.PUBLIC) {
            return SourceVisibility.INTERNAL;
        }
        return visibility;
    }

    private static Map<String, String> facts(Map<String, Object> requestFacts, UserContext user) {
        Map<String, String> out = new LinkedHashMap<>();
        if (requestFacts != null) {
            requestFacts.forEach((key, value) -> {
                if (key != null && value != null) {
                    out.put(key, String.valueOf(value));
                }
            });
        }
        if (user != null) {
            put(out, "user_id", user.userId());
            put(out, "tenant_id", user.tenantId());
            put(out, "roles", join(user.roles()));
            put(out, "permissions", join(user.permissions()));
        }
        return out;
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            facts.put(key, value);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }
}
