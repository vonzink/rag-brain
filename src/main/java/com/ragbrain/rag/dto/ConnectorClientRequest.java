package com.ragbrain.rag.dto;

import java.util.List;
import java.util.UUID;

public record ConnectorClientRequest(
        String name,
        String type,
        UUID brainId,
        List<String> scopes,
        List<String> allowedOrigins,
        List<String> allowedPeerHosts,
        boolean enabled
) {
}
