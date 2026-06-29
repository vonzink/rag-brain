package com.msfg.rag.controller;

import com.msfg.rag.dto.BrainProfileDto;
import com.msfg.rag.dto.BrainProfileRequest;
import com.msfg.rag.service.profile.BrainProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai/admin/brains/{brainId}/profile")
public class AdminBrainProfileController {

    private final BrainProfileService service;

    public AdminBrainProfileController(BrainProfileService service) {
        this.service = service;
    }

    @GetMapping
    public BrainProfileDto get(@PathVariable UUID brainId) {
        return BrainProfileDto.from(service.getOrCreate(brainId));
    }

    @PutMapping
    public BrainProfileDto put(@PathVariable UUID brainId, @RequestBody BrainProfileRequest req) {
        validate(req);
        return BrainProfileDto.from(service.update(brainId, req));
    }

    private static void validate(BrainProfileRequest req) {
        if (req.confidenceTarget() < 0.0 || req.confidenceTarget() > 1.0) {
            throw new IllegalArgumentException("confidenceTarget must be between 0 and 1");
        }
    }
}
