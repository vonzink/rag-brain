package com.msfg.rag.service.connect;

import com.msfg.rag.domain.BrainConnectorClient;
import com.msfg.rag.domain.BrainConnectorEvent;
import com.msfg.rag.repository.BrainConnectorClientRepository;
import com.msfg.rag.repository.BrainConnectorEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ConnectorAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrainConnectorClientRepository clients;
    private final BrainConnectorEventRepository events;

    public ConnectorAuthService(BrainConnectorClientRepository clients,
                                BrainConnectorEventRepository events) {
        this.clients = clients;
        this.events = events;
    }

    @Transactional
    public String rotateToken(UUID connectorId) {
        BrainConnectorClient client = clients.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown connector: " + connectorId));
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = "rb_conn_" + HexFormat.of().formatHex(bytes);
        client.setTokenHash(hashToken(token));
        clients.save(client);
        return token;
    }

    @Transactional
    public BrainConnectorClient require(String authorizationHeader, String requiredScope,
                                        UUID brainId, String requestHost, String eventType) {
        return require(authorizationHeader, requiredScope, brainId, requestHost, null, eventType);
    }

    @Transactional
    public BrainConnectorClient require(String authorizationHeader, String requiredScope,
                                        UUID brainId, String requestHost, String origin, String eventType) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            record(null, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token is required");
        }

        BrainConnectorClient client = clients.findByTokenHash(hashToken(token)).orElse(null);
        if (client == null) {
            record(null, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token rejected");
        }
        if (!client.isEnabled()) {
            record(client, brainId, "AUTH_FAILURE", requiredScope, requestHost, "401");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector is disabled");
        }
        if (client.getBrainId() != null && brainId != null && !client.getBrainId().equals(brainId)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector is not allowed for this brain");
        }
        if (requiredScope != null && !client.getScopes().contains(requiredScope)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector scope is required: " + requiredScope);
        }
        if (!isPeerHostAllowed(client.getAllowedPeerHosts(), requestHost)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector peer host is not allowed");
        }
        if (!isOriginAllowed(client.getAllowedOrigins(), origin)) {
            record(client, brainId, eventType, requiredScope, requestHost, "403");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connector origin is not allowed");
        }

        client.setLastUsedAt(OffsetDateTime.now());
        clients.save(client);
        record(client, brainId, eventType, requiredScope, requestHost, "200");
        return client;
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void record(BrainConnectorClient client, UUID brainId, String eventType,
                        String scope, String requestHost, String status) {
        UUID clientId = client == null ? null : client.getId();
        events.save(new BrainConnectorEvent(UUID.randomUUID(), clientId, brainId,
                eventType == null ? "AUTH_FAILURE" : eventType, scope, requestHost, status));
    }

    private static boolean isPeerHostAllowed(List<String> allowedPeerHosts, String requestHost) {
        if (allowedPeerHosts == null || allowedPeerHosts.isEmpty()) {
            return true;
        }
        String host = normalizedHost(requestHost);
        return host != null && allowedPeerHosts.stream()
                .map(ConnectorAuthService::normalizedHost)
                .anyMatch(host::equals);
    }

    private static boolean isOriginAllowed(List<String> allowedOrigins, String origin) {
        if (allowedOrigins == null || allowedOrigins.isEmpty() || origin == null || origin.isBlank()) {
            return true;
        }
        String host = normalizedHost(origin);
        return host != null && allowedOrigins.stream()
                .map(ConnectorAuthService::normalizedHost)
                .anyMatch(host::equals);
    }

    private static String normalizedHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.contains("://")) {
            try {
                String host = URI.create(trimmed).getHost();
                return host == null ? null : host.toLowerCase(Locale.US);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        int slash = trimmed.indexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(0, slash);
        }
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            trimmed = trimmed.substring(0, colon);
        }
        trimmed = trimmed.strip();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.US);
    }

    private static String bearerToken(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            trimmed = trimmed.substring(7).trim();
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
