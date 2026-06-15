package com.msfg.rag.controller;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.pack.DomainPackLoader;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.sync.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

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

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    /** Create payload — configurable fields only. NO secret fields (no local key / base url). */
    public record CreateBrainRequest(
            String slug, String displayName, String packRef, String sourceType,
            String s3Bucket, String s3Prefix, String s3Region, String localPath,
            String answerProvider, String answerModel,
            String utilityProvider, String utilityModel) {}

    @PostMapping
    public BrainDto create(@RequestBody CreateBrainRequest req) {
        String slug = req.slug() == null ? "" : req.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match ^[a-z0-9-]+$ (got '" + req.slug() + "')");
        }
        if (brains.findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("A brain with slug '" + slug + "' already exists");
        }
        requireText("displayName", req.displayName());
        requireText("packRef", req.packRef());
        requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());
        validatePack(req.packRef().trim(), slug);

        Brain brain = new Brain(UUID.randomUUID(), slug, req.displayName().trim());
        apply(brain, req.packRef(), req.sourceType(), req.s3Bucket(), req.s3Prefix(), req.s3Region(),
                req.localPath(), req.answerProvider(), req.answerModel(),
                req.utilityProvider(), req.utilityModel());
        brain.setDefault(false);   // activation is a separate, explicit action
        brain.setActive(true);
        return BrainDto.from(brains.save(brain));
    }

    /** Update payload — same configurable fields as create. NO id, NO is_default, NO secrets. */
    public record UpdateBrainRequest(
            String slug, String displayName, String packRef, String sourceType,
            String s3Bucket, String s3Prefix, String s3Region, String localPath,
            String answerProvider, String answerModel,
            String utilityProvider, String utilityModel) {}

    @PutMapping("/{id}")
    public BrainDto update(@PathVariable UUID id, @RequestBody UpdateBrainRequest req) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));

        String slug = req.slug() == null ? "" : req.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match ^[a-z0-9-]+$ (got '" + req.slug() + "')");
        }
        brains.findBySlug(slug).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new IllegalArgumentException("A brain with slug '" + slug + "' already exists");
            }
        });
        requireText("displayName", req.displayName());
        requireText("packRef", req.packRef());
        requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());
        validatePack(req.packRef().trim(), slug);

        boolean packChanged = !Objects.equals(brain.getPackRef(), trimToNull(req.packRef()));
        brain.setSlug(slug);
        brain.setDisplayName(req.displayName().trim());
        apply(brain, req.packRef(), req.sourceType(), req.s3Bucket(), req.s3Prefix(), req.s3Region(),
                req.localPath(), req.answerProvider(), req.answerModel(),
                req.utilityProvider(), req.utilityModel());
        Brain saved = brains.save(brain);
        if (packChanged) {
            packRegistry.reload(id);   // next request reloads + re-validates the new pack
        }
        return BrainDto.from(saved);
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public BrainDto activate(@PathVariable UUID id) {
        Brain target = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        if (!target.isActive()) {
            throw new IllegalArgumentException("Cannot activate an inactive brain: " + id);
        }
        // Clear the existing default FIRST and flush — the partial unique index
        // ux_brains_single_default rejects two is_default=true rows at statement time.
        brains.findDefaultBrain().ifPresent(current -> {
            if (!current.getId().equals(id)) {
                current.setDefault(false);
                brains.saveAndFlush(current);
            }
        });
        target.setDefault(true);
        return BrainDto.from(brains.save(target));
    }

    /** Loads the pack at packRef and asserts its slug equals the brain slug — clean 400 on any problem. */
    void validatePack(String packRef, String slug) {
        DomainPack pack;
        try {
            pack = new DomainPackLoader().load(Path.of(packRef).toAbsolutePath().normalize());
        } catch (DomainPackLoader.PackValidationException e) {
            throw new IllegalArgumentException("Invalid pack at '" + packRef + "': " + e.getMessage());
        }
        if (!pack.slug().equals(slug)) {
            throw new IllegalArgumentException("Pack at '" + packRef + "' has slug '" + pack.slug()
                    + "' but the brain slug is '" + slug + "' — they must match");
        }
    }

    private void apply(Brain brain, String packRef, String sourceType, String s3Bucket,
                       String s3Prefix, String s3Region, String localPath,
                       String answerProvider, String answerModel,
                       String utilityProvider, String utilityModel) {
        brain.setPackRef(trimToNull(packRef));
        brain.setSourceType(sourceType == null ? null : sourceType.trim().toLowerCase(Locale.US));
        if ("s3".equalsIgnoreCase(sourceType)) {
            brain.setS3Bucket(trimToNull(s3Bucket));
            brain.setS3Prefix(trimToNull(s3Prefix));
            brain.setS3Region(trimToNull(s3Region));
            brain.setLocalPath(null);
        } else { // local
            brain.setLocalPath(trimToNull(localPath));
            brain.setS3Bucket(null);
            brain.setS3Prefix(null);
            brain.setS3Region(null);
        }
        brain.setAnswerProvider(trimToNull(answerProvider));
        brain.setAnswerModel(trimToNull(answerModel));
        brain.setUtilityProvider(trimToNull(utilityProvider));
        brain.setUtilityModel(trimToNull(utilityModel));
    }

    private void requireSourceBinding(String sourceType, String localPath, String s3Bucket) {
        String t = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.US);
        if (t.equals("local")) {
            if (isBlank(localPath)) throw new IllegalArgumentException("localPath is required for a local source");
        } else if (t.equals("s3")) {
            if (isBlank(s3Bucket)) throw new IllegalArgumentException("s3Bucket is required for an s3 source");
        } else {
            throw new IllegalArgumentException("sourceType must be 'local' or 's3' (got '" + sourceType + "')");
        }
    }

    private static void requireText(String field, String v) {
        if (isBlank(v)) throw new IllegalArgumentException(field + " is required");
    }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String trimToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
