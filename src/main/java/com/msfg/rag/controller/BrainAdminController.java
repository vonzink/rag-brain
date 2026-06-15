package com.msfg.rag.controller;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.sync.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for brains: list / create / configure / activate / sync (and a
 * soft-delete). Lives under /api/ai/admin so AdminApiKeyFilter gates it
 * (X-Admin-Api-Key). Never exposes local_api_key_ref / local_base_url — the
 * DTO has no field for them. Validation failures the admin can fix are thrown
 * as IllegalArgumentException so GlobalExceptionHandler returns a clean 400.
 */
@RestController
@RequestMapping("/api/ai/admin/brains")
public class BrainAdminController {

    /** Non-secret admin view of a brain. NO local_api_key_ref / local_base_url. */
    public record BrainDto(
            UUID id, String slug, String displayName, String packRef,
            String sourceType, String s3Bucket, String s3Prefix, String s3Region,
            String localPath, String answerProvider, String answerModel,
            String utilityProvider, String utilityModel,
            boolean isDefault, boolean isActive) {

        static BrainDto from(Brain b) {
            return new BrainDto(
                    b.getId(), b.getSlug(), b.getDisplayName(), b.getPackRef(),
                    b.getSourceType(), b.getS3Bucket(), b.getS3Prefix(), b.getS3Region(),
                    b.getLocalPath(), b.getAnswerProvider(), b.getAnswerModel(),
                    b.getUtilityProvider(), b.getUtilityModel(),
                    b.isDefault(), b.isActive());
        }
    }

    private final BrainRepository brains;
    private final SyncService syncService;
    private final DomainPackRegistry packRegistry;
    private final ModelRouterService router;

    public BrainAdminController(BrainRepository brains, SyncService syncService,
                               DomainPackRegistry packRegistry, ModelRouterService router) {
        this.brains = brains;
        this.syncService = syncService;
        this.packRegistry = packRegistry;
        this.router = router;
    }

    @GetMapping
    public List<BrainDto> list() {
        return brains.findAll().stream().map(BrainDto::from).toList();
    }

    @GetMapping("/{id}")
    public BrainDto get(@PathVariable UUID id) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        return BrainDto.from(brain);
    }
}
