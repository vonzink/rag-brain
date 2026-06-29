package com.msfg.rag.controller;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.BrainResolver;
import com.msfg.rag.service.publicapi.PublicAccessService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Public website endpoint. Rate limiting is applied by RateLimitFilter.
 */
@RestController
@RequestMapping("/api/ai/${brain.slug:generic}")
public class AskController {

    private final AskService askService;
    private final BrainResolver brainResolver;
    private final PublicAccessService publicAccessService;
    private final String adminApiKey;

    public AskController(AskService askService, BrainResolver brainResolver,
                         PublicAccessService publicAccessService, RagProperties properties) {
        this.askService = askService;
        this.brainResolver = brainResolver;
        this.publicAccessService = publicAccessService;
        this.adminApiKey = properties.admin().apiKey();
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request,
                                           @RequestParam(value = "brain", required = false) String brain,
                                           @RequestHeader(value = "X-Admin-Api-Key", required = false) String adminApiKey,
                                           @RequestHeader(value = "X-Public-Brain-Token", required = false) String publicBrainToken,
                                           @RequestHeader(value = "Origin", required = false) String origin) {
        UUID brainId = brainResolver.resolve(brain).getId();
        if (hasValidAdminApiKey(adminApiKey)) {
            return ResponseEntity.ok(askService.ask(request, brainId, adminVisibility(request.surface())));
        }

        publicAccessService.validate(brainId, publicBrainToken, origin);
        AskRequest publicRequest = new AskRequest(
                request.conversationId(),
                request.sessionId(),
                request.question(),
                request.loanType(),
                request.state(),
                request.pageRoute(),
                SourceVisibility.PUBLIC.name());
        return ResponseEntity.ok(askService.ask(publicRequest, brainId, SourceVisibility.PUBLIC));
    }

    private boolean hasValidAdminApiKey(String providedApiKey) {
        if (providedApiKey == null || adminApiKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                providedApiKey.getBytes(StandardCharsets.UTF_8),
                adminApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private SourceVisibility adminVisibility(String requestedSurface) {
        if (requestedSurface == null || requestedSurface.isBlank()) {
            return SourceVisibility.INTERNAL;
        }
        try {
            return SourceVisibility.valueOf(requestedSurface.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SourceVisibility.INTERNAL;
        }
    }
}
