package com.msfg.rag.controller;

import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.BrainResolver;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public website endpoint. Rate limiting is applied by RateLimitFilter.
 */
@RestController
@RequestMapping("/api/ai/${brain.slug:mortgage}")
public class AskController {

    private final AskService askService;
    private final BrainResolver brainResolver;

    public AskController(AskService askService, BrainResolver brainResolver) {
        this.askService = askService;
        this.brainResolver = brainResolver;
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request,
                                           @RequestParam(value = "brain", required = false) String brain) {
        UUID brainId = brainResolver.resolve(brain).getId();
        return ResponseEntity.ok(askService.ask(request, brainId));
    }
}
