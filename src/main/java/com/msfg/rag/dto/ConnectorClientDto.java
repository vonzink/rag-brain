package com.msfg.rag.dto;

import com.msfg.rag.domain.BrainConnectorClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConnectorClientDto(
        UUID id,
        String name,
        String type,
        UUID brainId,
        List<String> scopes,
        List<String> allowedOrigins,
        List<String> allowedPeerHosts,
        boolean enabled,
        boolean hasToken,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ConnectorClientDto from(BrainConnectorClient client) {
        return new ConnectorClientDto(
                client.getId(),
                client.getName(),
                client.getType(),
                client.getBrainId(),
                List.copyOf(client.getScopes()),
                List.copyOf(client.getAllowedOrigins()),
                List.copyOf(client.getAllowedPeerHosts()),
                client.isEnabled(),
                client.getTokenHash() != null && !client.getTokenHash().isBlank(),
                client.getLastUsedAt(),
                client.getCreatedAt(),
                client.getUpdatedAt());
    }
}
