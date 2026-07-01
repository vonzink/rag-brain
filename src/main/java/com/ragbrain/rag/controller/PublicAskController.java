package com.ragbrain.rag.controller;

import com.ragbrain.rag.dto.PublicAskRequest;
import com.ragbrain.rag.dto.PublicAskResponse;
import com.ragbrain.rag.service.publicapi.PublicAskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/public/{slug}")
public class PublicAskController {

    private final PublicAskService service;

    public PublicAskController(PublicAskService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public ResponseEntity<PublicAskResponse> ask(@PathVariable String slug,
                                                 @RequestHeader(value = "X-Public-Brain-Token", required = false) String token,
                                                 @RequestHeader(value = "Origin", required = false) String origin,
                                                 @Valid @RequestBody PublicAskRequest request) {
        return ResponseEntity.ok(service.ask(slug, token, origin, request));
    }
}
