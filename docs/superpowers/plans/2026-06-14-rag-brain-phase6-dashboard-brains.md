# rag-brain Phase 6 (MVP) — Dashboard Brains Screen + Brain-CRUD Admin API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax. Backend tasks are TDD (write the failing test first); frontend tasks are build-green (`npm run check` + `npm test`). All work happens in `/Users/zacharyzink/rag-brain`; **never read or touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Goal:** Let an admin create, configure, activate, and sync brains from the dashboard. Headline flow: **create a brain → set its source to a local folder (or S3 bucket/prefix) → pick its pack + model → Sync now → set active** — all from a new **Brains** screen. The engine already exists (Phases 1–5); this phase adds the admin API (`BrainAdminController` under `/api/ai/admin/brains`) and the React UI over it. This is what makes "connect a folder from the dashboard" real.

**Architecture:** A new `BrainAdminController` under `/api/ai/admin/brains` (so the existing `AdminApiKeyFilter` gate applies — it only filters paths starting with `/api/ai/documents` or `/api/ai/admin`). It exposes list / create / update / activate / sync (and an optional soft-delete). Create/update validate the request (slug uniqueness + `^[a-z0-9-]+$`; the pack at `packRef` loads via `DomainPackLoader` and its `slug` equals the brain slug; the source binding for the chosen type is present) and persist through `BrainRepository`. Activate flips the single default brain in one transaction, respecting the partial unique index `ux_brains_single_default` (clear the old default first). Sync delegates to `SyncService.sync(dryRun, brainId)`. A new `BrainDto` carries only non-secret fields. The frontend adds a `Brains` screen (mirroring `Settings.tsx`/`Corpus.tsx` patterns exactly) wired through new `api.ts` methods and registered in `App.tsx` nav.

**Tech Stack:** Java 21 · Spring Boot · JPA/Hibernate · JUnit 5 + Mockito (controller unit tests) + Testcontainers (repository tests) · React 18 + TypeScript + Vite · Vitest.

---

## Context (verified — these are real signatures/snippets read from the repo)

### Auth gate (the 401 the new endpoints inherit)
`config/AdminApiKeyFilter.java` gates only admin surfaces; everything else is public:
```java
String path = PATH_HELPER.getPathWithinApplication(request);
return !(path.startsWith("/api/ai/documents") || path.startsWith("/api/ai/admin"));
```
A missing/invalid `X-Admin-Api-Key` → `401` with `{"error":"Missing or invalid admin API key"}`. **New brain endpoints MUST live under `/api/ai/admin/brains` so they are gated.** There is **no MockMvc / `@WebMvcTest` / `@SpringBootTest` HTTP harness anywhere in the repo** (verified by grep); the 401 gate is proven by `AdminApiKeyFilterTest` asserting `shouldNotFilter(...)` on each gated path. We mirror that exactly — add an assertion that `/api/ai/admin/brains` is gated.

`config/AdminApiKeyFilterTest.java` (the pattern to extend):
```java
@Test
void gatesDocumentsAndAdminSurfaces() {
    assertFalse(filter.shouldNotFilter(get("/api/ai/documents")));
    assertFalse(filter.shouldNotFilter(get("/api/ai/admin/settings")));
    assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")));
    assertTrue(filter.shouldNotFilter(get("/api/ai/conversations/abc")));
}
```
The `RagProperties` test constructor it uses:
```java
new RagProperties(
    new RagProperties.Routing("anthropic", "openai"),
    new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
    new RagProperties.Chunking(1000, 1200, 150),
    new RagProperties.Storage("./data/documents"),
    new RagProperties.Admin("k"),
    new RagProperties.RateLimit(10));
```

### CORS — already covers the new path, no change needed
`config/CorsConfig.java` already maps `/api/ai/admin/**` with `allowedMethods("GET", "PUT", "POST", "OPTIONS")` and `allowedHeaders("Content-Type", "X-Admin-Api-Key")`. Our new endpoints are GET/POST/PUT (+ optional DELETE) under `/api/ai/admin/brains` — covered. **If we add `DELETE`, we must add `"DELETE"` to that mapping's `allowedMethods`** (noted in Task 6).

### Error mapping (controllers throw, the advice maps)
`exception/GlobalExceptionHandler.java`:
- `IllegalArgumentException` → **400** `{"error": <message>}`.
- `MaxUploadSizeExceededException` → 413.
- catch-all `Exception` → **500** with a generic message (this includes `IllegalStateException`).

**Design consequence:** validation failures the user can fix (bad slug, duplicate slug, pack/slug mismatch, missing source binding) MUST be thrown as `IllegalArgumentException` (→ clean 400). Do **not** rely on `DomainPackRegistry.bundle()` for create-time validation: it throws `IllegalStateException` on a slug mismatch (→ generic 500). Validate the pack in the controller via `DomainPackLoader` (load + slug-check) and throw `IllegalArgumentException`.

### Brain entity + repository
`domain/Brain.java` — `@Entity @Table(name = "brains")`. Public no-arg ctor is `protected`; the usable ctor is `public Brain(UUID id, String slug, String displayName)`. Setters exist for every configurable column: `setSlug`, `setDisplayName`, `setPackRef`, `setSourceType`, `setS3Bucket`, `setS3Prefix`, `setS3Region`, `setLocalPath`, `setAnswerProvider`, `setAnswerModel`, `setUtilityProvider`, `setUtilityModel`, `setLocalBaseUrl`, `setLocalApiKeyRef`, `setDefault`, `setActive`. Getters: `getId`, `getSlug`, `getDisplayName`, `getPackRef`, `getSourceType`, `getS3Bucket`, `getS3Prefix`, `getS3Region`, `getLocalPath`, `getAnswerProvider`, `getAnswerModel`, `getUtilityProvider`, `getUtilityModel`, `getLocalBaseUrl`, `getLocalApiKeyRef`, `isDefault`, `isActive`, `getCreatedAt`, `getUpdatedAt`. `@PrePersist`/`@PreUpdate` stamp `created_at`/`updated_at`; `active` defaults to `true`. **`local_api_key_ref` and `local_base_url` are secrets/SSRF-surface and must NEVER appear in any DTO or be settable in this MVP.**

`repository/BrainRepository.java`:
```java
public interface BrainRepository extends JpaRepository<Brain, UUID> {
    @Query("select b from Brain b where b.isDefault = true")
    Optional<Brain> findDefaultBrain();
    Optional<Brain> findBySlug(String slug);
}
```
`findAll()`, `findById(UUID)`, `save(...)`, `saveAndFlush(...)` come from `JpaRepository`.

### The partial unique index (governs "Set active")
`db/migration/V7__add_brains_and_brain_id.sql`:
```sql
CREATE UNIQUE INDEX ux_brains_single_default ON brains (is_default) WHERE is_default;
```
`repository/BrainRepositoryTest.java` proves a second `is_default=true` row throws `DataIntegrityViolationException`. The index is **not deferred** — it is enforced per statement. So activate must **clear the old default (save + flush) BEFORE setting the new one**, inside one `@Transactional` method. Well-known seeded default id: `00000000-0000-0000-0000-000000000001` (`TestBrains.DEFAULT_ID`).

### Pack loading + validation
`pack/DomainPackLoader.java` is a plain class with a public no-arg ctor (`new DomainPackLoader()`) and `public DomainPack load(Path packDir)`. It throws `DomainPackLoader.PackValidationException` (a `RuntimeException`) naming the file/field on any problem, and rejects a bad slug itself (`slug (must match [a-z0-9-]+)`). `DomainPack` exposes `slug()` and `companyName()` (used by `AdminStatsController`).
`pack/DomainPackRegistry.java` — `bundle(UUID brainId)` loads+validates+caches a brain's pack (throws `IllegalStateException` on slug mismatch); `reload(UUID brainId)` evicts the cache so the next call reloads. **Call `reload(id)` after an update that changes `packRef`.**

### Sync (already brain-scoped)
`service/sync/SyncService.java` — `public SyncReport sync(boolean dryRun, UUID brainId)` loads the brain, builds its `CorpusSource` via `CorpusSourceFactory.forBrain(brain)` (S3 or local), plans, ingests. Throws `IllegalArgumentException("Unknown brain: " + brainId)` if the id is unknown. `service/sync/SyncReport.java`:
```java
public record SyncReport(boolean dryRun, Map<String,Integer> summary, List<Result> results) {
    public record Result(String fileName, String action, String reason,
                         boolean executed, boolean succeeded, String error) {}
}
```
`DocumentAdminController.sync(dryRun, @RequestParam brain)` already exposes sync **by slug** via `?brain=`. **Decision (see Ambiguities):** the new Brains screen syncs by **id** through `POST /api/ai/admin/brains/{id}/sync` — one consistent id-keyed surface for all brain admin actions — calling the same `SyncService.sync(dryRun, brainId)`. The existing slug-keyed document sync stays untouched.

### Providers list (for the model dropdowns)
`service/ai/ModelRouterService.java` — `public Set<String> providerNames()` returns registered providers. `AdminSettingsController.get()` already surfaces a `providers` block:
```java
private static final List<String> KNOWN_PROVIDERS =
        List.of("anthropic", "openai", "deepseek", "gemini", "grok");
...
Set<String> configured = router.providerNames();
List<Map<String,Object>> providers = KNOWN_PROVIDERS.stream()
        .map(n -> Map.<String,Object>of("name", n, "configured", configured.contains(n)))
        .toList();
```
**Decision:** the Brains screen reuses the existing `GET /api/ai/admin/settings` `providers` block for the provider dropdowns (no new providers endpoint). Model is a free-text input (mirrors Settings' "blank = provider default" model fields). This avoids a new endpoint and matches the existing UX.

### DTO style (controller-local records, like the rest of the admin controllers)
`controller/AdminStatsController.java` declares its DTOs as nested records:
```java
public record BrainDto(String companyName, String slug) {}
public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
```
`AdminSettingsController` returns plain `Map`/records, validates inline, throws `IllegalArgumentException`. `dto/DocumentDto.java` is a top-level record with a `static from(entity)` mapper. **Decision:** our admin `BrainDto` + request records live **controller-local** (nested records in `BrainAdminController`), matching `AdminStatsController`. (Note: `AdminStatsController` already has an unrelated tiny `BrainDto(companyName, slug)`; ours is a distinct nested type in a different controller — no collision. The frontend type is named `BrainAdminDto` to avoid confusion with the existing `Stats.brain` shape.)

### Controller test style (plain JUnit + Mockito — NO Spring context)
`controller/AdminStatsControllerTest.java`, `controller/AdminSettingsControllerTest.java`, `controller/DocumentAdminControllerSyncTest.java` all `new` the controller with mocked collaborators and assert returned DTOs / verify interactions. Example collaborator stub:
```java
Brain brain = new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage");
when(brainResolver.resolve(any())).thenReturn(brain);
```
Repository behaviors that need a real DB use `@DataJpaTest @AutoConfigureTestDatabase(replace = NONE) @Testcontainers` with the pgvector container (see `BrainRepositoryTest`). **We use a plain Mockito unit test for `BrainAdminController` and a `@DataJpaTest` Testcontainers test only for the activate single-default invariant.**

### Frontend conventions (mirror exactly)
- `dashboard/src/api.ts` — `adminKey` (sessionStorage), `request<T>` sets `X-Admin-Api-Key`, JSON-encodes non-FormData bodies, clears key + throws `AuthError` on 401, throws `body.error` on other failures. Exposed verbs: `api.get`, `api.post(path, body?)`, `api.put(path, body)`, `api.upload`. **All paths are relative** (vite proxies `/api` → `http://localhost:8090`).
- `dashboard/src/types.ts` — shared interfaces (`DocumentDto`, `SyncReport`, `SettingsResponse`, etc.). We add `BrainAdminDto` + `BrainCreateRequest` here.
- `dashboard/src/components.tsx` — `Pill({tone})` (`"green"|"amber"|"gray"|"blue"|"purple"`), `Stat`, `ErrorNote({message})`. Reuse these; do not invent new primitives.
- `dashboard/src/screens/Settings.tsx` — provider `<select>` from `data.providers.filter(p=>p.configured)`, draft/save state, `ErrorNote`, `Pill`. **Mirror its provider dropdown + form-state pattern.**
- `dashboard/src/screens/Corpus.tsx` — table of rows with per-row action buttons, a `busy` string-id pattern, a sync `report` block rendering `SyncReport`. **Mirror its row-action + sync-report rendering.**
- `dashboard/src/App.tsx` — screens imported at top, listed in `<nav className="nav">` as `<NavLink>`, routed in `<Routes>`. We add the `Brains` import, NavLink, and Route.
- `dashboard/package.json` scripts: `build` (`vite build`), `check` (`tsc --noEmit`), `test` (`vitest`). **Green bar = `npm run check` passes AND `npm test` passes AND `npm run build` succeeds.**
- `dashboard/src/api.test.ts` exists (Vitest, node env, `src/**/*.test.ts`). We add a small api-client test for the new methods (path + verb), mirroring its `fetchReturning`/`vi.stubGlobal("fetch", ...)` pattern.

---

### Task 0: Green baseline

- [ ] **Backend:** `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL`. Red → stop.
- [ ] **Frontend:** `cd /Users/zacharyzink/rag-brain/dashboard && npm install && npm run check && npm test -- --run && npm run build` → all green. Red → stop.
- [ ] **Confirm no prior brains endpoint:** `grep -rn "admin/brains" src dashboard/src` → no matches (the path is new).
- [ ] **Confirm isolation:** `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` is untouched by this work (we never enter that repo).

---

### Task 1: `BrainDto` + list/get — `GET /api/ai/admin/brains` (TDD)

**Files:**
- Create: `src/main/java/com/msfg/rag/controller/BrainAdminController.java`
- Create test: `src/test/java/com/msfg/rag/controller/BrainAdminControllerTest.java`
- Modify: `src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java` (assert the new path is gated → proves 401)

- [ ] **Step 1: Write the failing test** `BrainAdminControllerTest.java`. Start with the gate assertion + a `list` test. The controller takes (`BrainRepository`, `SyncService`, `DomainPackRegistry`, `ModelRouterService` — only the deps each task needs; add them as tasks grow). For Task 1 it needs only `BrainRepository`.

```java
package com.msfg.rag.controller;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrainAdminControllerTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final DomainPackRegistry packRegistry = mock(DomainPackRegistry.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final BrainAdminController controller =
            new BrainAdminController(brains, syncService, packRegistry, router);

    private Brain brain(UUID id, String slug, boolean isDefault, boolean active) {
        Brain b = new Brain(id, slug, slug + " brain");
        b.setPackRef("packs/" + slug);
        b.setSourceType("local");
        b.setLocalPath("/corpora/" + slug);
        b.setAnswerProvider("anthropic");
        b.setAnswerModel("claude-haiku-4-5");
        b.setUtilityProvider("openai");
        b.setUtilityModel("gpt-4.1-nano");
        b.setLocalApiKeyRef("secret-ref");   // must never surface in the DTO
        b.setDefault(isDefault);
        b.setActive(active);
        return b;
    }

    @Test
    void listReturnsEverBrainAsDtoWithoutSecrets() {
        UUID id = UUID.randomUUID();
        when(brains.findAll()).thenReturn(List.of(brain(id, "mortgage", true, true)));

        List<BrainAdminController.BrainDto> dtos = controller.list();

        assertEquals(1, dtos.size());
        BrainAdminController.BrainDto dto = dtos.get(0);
        assertEquals(id, dto.id());
        assertEquals("mortgage", dto.slug());
        assertEquals("packs/mortgage", dto.packRef());
        assertEquals("local", dto.sourceType());
        assertEquals("/corpora/mortgage", dto.localPath());
        assertEquals("anthropic", dto.answerProvider());
        assertEquals("claude-haiku-4-5", dto.answerModel());
        assertEquals(true, dto.isDefault());
        assertEquals(true, dto.isActive());
        // Secret-exposure guard: the DTO has no accessor that returns the key ref.
        assertFalse(dto.toString().contains("secret-ref"),
                "BrainDto must never carry local_api_key_ref / local_base_url");
    }

    @Test
    void getByIdReturnsTheBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
        assertEquals("lending", controller.get(id).slug());
    }
}
```

- [ ] **Step 2: Run → FAIL** (controller doesn't exist): `./gradlew test --tests "com.msfg.rag.controller.BrainAdminControllerTest"`.

- [ ] **Step 3: Implement the controller skeleton + `BrainDto` + list/get.**

```java
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
```

- [ ] **Step 4: Add the 401-gate assertion** to `AdminApiKeyFilterTest.gatesDocumentsAndAdminSurfaces` (proves an unauthenticated call to the new path is filtered → 401). Insert this line with the others:
```java
assertFalse(filter.shouldNotFilter(get("/api/ai/admin/brains")));
```
(The filter matches by prefix `/api/ai/admin`, so `/api/ai/admin/brains` and `/api/ai/admin/brains/{id}/sync` are all gated. No filter code change — only the test asserting the new surface.)

- [ ] **Step 5: Run → PASS**: `./gradlew test --tests "com.msfg.rag.controller.BrainAdminControllerTest" --tests "com.msfg.rag.config.AdminApiKeyFilterTest"`, then full `./gradlew test`.

- [ ] **Step 6: Commit** (heredoc message):
```
Phase 6: list/get brains admin API (non-secret BrainDto)

GET /api/ai/admin/brains and /{id} return a BrainDto that omits
local_api_key_ref / local_base_url. AdminApiKeyFilterTest now asserts the new
path is gated (unauthenticated -> 401).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 2: Create — `POST /api/ai/admin/brains` (TDD)

**Files:**
- Modify: `src/main/java/com/msfg/rag/controller/BrainAdminController.java`
- Modify test: `src/test/java/com/msfg/rag/controller/BrainAdminControllerTest.java`

Validation (all → `IllegalArgumentException`/400):
1. `slug` non-blank, matches `^[a-z0-9-]+$`, and is unique (`brains.findBySlug(slug).isPresent()` → reject).
2. `displayName` non-blank.
3. `packRef` non-blank; the pack at `packRef` **loads** via `DomainPackLoader` and its `slug` **equals** the brain slug. A pack load/validation failure or slug mismatch → a clear 400 (wrap `PackValidationException` and the mismatch in `IllegalArgumentException`). *(We use the loader directly, not `DomainPackRegistry.bundle()`, because the registry throws `IllegalStateException` → generic 500.)*
4. `sourceType` is `local` or `s3`. For `local`: `localPath` non-blank. For `s3`: `s3Bucket` non-blank (`s3Prefix`/`s3Region` optional).
5. Generate the id (`UUID.randomUUID()`); set fields; `isDefault=false` on create (never seed a default here); `isActive=true`. Persist; return `201`-style body (controllers here return the DTO directly — keep it consistent with `DocumentAdminController` which returns `ResponseEntity.ok(dto)`; we return the DTO directly like `AdminStatsController`/`AdminSettingsController`).

- [ ] **Step 1: Write failing tests** (append to `BrainAdminControllerTest`):
```java
@Test
void createPersistsAndReturnsDtoForALocalBrain() {
    when(brains.findBySlug("lending")).thenReturn(Optional.empty());
    when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
    // Pack validation is exercised via a real on-disk pack: see createValidatesPackSlug.
    // Here, stub the pack check seam to accept (see implementation note on validatePack).

    BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
            "lending", "Lending Brain", "packs/test-pack", "local",
            null, null, null, "/corpora/lending",
            "anthropic", "claude-haiku-4-5", "openai", "gpt-4.1-nano");
    // ...assert via a controller seam that doesn't touch the filesystem (see note).
}

@Test
void createRejectsBadSlug() {
    BrainAdminController.CreateBrainRequest req = req("Bad Slug", "local");
    assertThrows(IllegalArgumentException.class, () -> controller.create(req));
}

@Test
void createRejectsDuplicateSlug() {
    when(brains.findBySlug("mortgage")).thenReturn(Optional.of(brain(UUID.randomUUID(), "mortgage", true, true)));
    assertThrows(IllegalArgumentException.class, () -> controller.create(req("mortgage", "local")));
}

@Test
void createRejectsLocalWithoutPath() {
    BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
            "lending", "Lending", "packs/test-pack", "local",
            null, null, null, "   ", "anthropic", "m", "openai", "u");
    assertThrows(IllegalArgumentException.class, () -> controller.create(req));
}

@Test
void createRejectsS3WithoutBucket() {
    BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
            "lending", "Lending", "packs/test-pack", "s3",
            "", "p/", "us-west-1", null, "anthropic", "m", "openai", "u");
    assertThrows(IllegalArgumentException.class, () -> controller.create(req));
}

// helper
private BrainAdminController.CreateBrainRequest req(String slug, String sourceType) {
    return new BrainAdminController.CreateBrainRequest(
            slug, "Display", "packs/test-pack", sourceType,
            "bucket", "p/", "us-west-1", "/corpora/x",
            "anthropic", "m", "openai", "u");
}
```

**Pack-validation seam note (important — read before implementing):** so the unit test never needs a real pack on disk for the slug/duplicate/source cases, the pack check is a **package-private overridable method** `void validatePack(String packRef, String slug)` on the controller. Cases that should pass the pack check (`createPersistsAndReturnsDtoForALocalBrain`) override it in an anonymous subclass to a no-op; cases that fail earlier (bad slug, dup slug, missing source) never reach it. A **separate** test (`createValidatesPackSlugAgainstRealPack`) does NOT override it and points `packRef` at the **on-disk relative path** `src/test/resources/packs/test-pack` (the existing fixture used by `TestPacks`). VERIFIED: that pack's `pack.yaml` has `slug: testco`. `DomainPackLoader.load(Path)` reads from the filesystem (not the classpath), and the test runs with the module root as the working directory, so `Path.of("src/test/resources/packs/test-pack")` resolves correctly. The passing case therefore uses brain slug **`testco`** (matches → passes); a second case uses any other slug (e.g. `"mismatch"`) with the same `packRef` to assert the slug-mismatch `IllegalArgumentException`. Build the `CreateBrainRequest` for these two cases with `slug = "testco"` / `"mismatch"`, `packRef = "src/test/resources/packs/test-pack"`, `sourceType = "local"`, a non-blank `localPath`, and stub `brains.findBySlug(...)` → empty so the unique check passes first.

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement create.** Add to `BrainAdminController`:
```java
import com.msfg.rag.pack.DomainPackLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.nio.file.Path;
import java.util.regex.Pattern;

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

/** Loads the pack at packRef and asserts its slug equals the brain slug — clean 400 on any problem. */
void validatePack(String packRef, String slug) {
    var pack = (com.msfg.rag.pack.DomainPack) null;
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
    brain.setSourceType(sourceType == null ? null : sourceType.trim().toLowerCase(java.util.Locale.US));
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
    String t = sourceType == null ? "" : sourceType.trim().toLowerCase(java.util.Locale.US);
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
```

- [ ] **Step 4: Run → PASS** (`BrainAdminControllerTest`), then full `./gradlew test`.

- [ ] **Step 5: Commit** (heredoc):
```
Phase 6: create brain admin API (POST /api/ai/admin/brains)

Validates slug (^[a-z0-9-]+$ + unique), pack loads and its slug matches the
brain slug, and the source binding (local_path or s3_bucket) is present. Never
sets is_default on create; no secret fields in the request DTO.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 3: Update — `PUT /api/ai/admin/brains/{id}` (TDD)

**Files:**
- Modify: `BrainAdminController.java`
- Modify test: `BrainAdminControllerTest.java`

Rules: update the configurable fields only (never the id, never `is_default`, never secrets). Re-validate exactly as create (slug regex; uniqueness check **excluding this brain's own id**; pack loads + slug matches; source binding present). On success, if `packRef` changed call `packRegistry.reload(id)` so the next request reloads the pack.

- [ ] **Step 1: Write failing tests:**
```java
@Test
void updateRevalidatesAndReloadsPackWhenPackRefChanges() {
    UUID id = UUID.randomUUID();
    Brain existing = brain(id, "lending", false, true);
    existing.setPackRef("packs/old");
    when(brains.findById(id)).thenReturn(Optional.of(existing));
    when(brains.findBySlug("lending")).thenReturn(Optional.of(existing)); // self -> allowed
    when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

    var probe = new java.util.concurrent.atomic.AtomicReference<UUID>();
    BrainAdminController c = new BrainAdminController(brains, syncService, packRegistry, router) {
        @Override void validatePack(String packRef, String slug) { /* accept */ }
    };
    // packRegistry.reload(id) is a mock; verify it is called when packRef changes:
    BrainAdminController.UpdateBrainRequest req = new BrainAdminController.UpdateBrainRequest(
            "lending", "Lending", "packs/new", "local",
            null, null, null, "/corpora/lending", "anthropic", "m", "openai", "u");
    c.update(id, req);
    org.mockito.Mockito.verify(packRegistry).reload(id);
}

@Test
void updateRejectsSlugTakenByAnotherBrain() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
    when(brains.findBySlug("mortgage")).thenReturn(Optional.of(brain(UUID.randomUUID(), "mortgage", true, true)));
    BrainAdminController.UpdateBrainRequest req = new BrainAdminController.UpdateBrainRequest(
            "mortgage", "X", "packs/test-pack", "local",
            null, null, null, "/x", "anthropic", "m", "openai", "u");
    BrainAdminController c = new BrainAdminController(brains, syncService, packRegistry, router) {
        @Override void validatePack(String packRef, String slug) { }
    };
    assertThrows(IllegalArgumentException.class, () -> c.update(id, req));
}
```
(For the override-subclass to compile, `validatePack` must be package-private and the test in the same package — it is.)

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement update:**
```java
import org.springframework.web.bind.annotation.PutMapping;

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

    boolean packChanged = !java.util.Objects.equals(brain.getPackRef(), trimToNull(req.packRef()));
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
```

- [ ] **Step 4: Run → PASS**, then full `./gradlew test`.

- [ ] **Step 5: Commit** (heredoc):
```
Phase 6: update brain admin API (PUT /api/ai/admin/brains/{id})

Re-validates slug/pack/source (slug-uniqueness excludes self); reloads the
pack via DomainPackRegistry.reload(id) when packRef changes. Never touches the
id, is_default, or secret fields.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 4: Activate — `POST /api/ai/admin/brains/{id}/activate` (TDD + Testcontainers invariant)

**Files:**
- Modify: `BrainAdminController.java`
- Modify test: `BrainAdminControllerTest.java` (unit: ordering)
- Create test: `src/test/java/com/msfg/rag/controller/BrainAdminActivateInvariantTest.java` (`@DataJpaTest` Testcontainers — proves the single-default invariant survives the real partial unique index)

Behavior: in ONE `@Transactional` method, set the activated brain `is_default=true` and clear the previous default — **clear the old default first (save + flush), then set the new one**, because `ux_brains_single_default` is enforced per statement (two true rows momentarily = violation). Also ensure the brain being activated `is_active=true` (you can't make an inactive brain the default). Return the updated `BrainDto`.

- [ ] **Step 1: Write the failing unit test** (ordering via Mockito `InOrder`):
```java
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;

@Test
void activateClearsOldDefaultBeforeSettingNew() {
    UUID oldId = UUID.randomUUID();
    UUID newId = UUID.randomUUID();
    Brain old = brain(oldId, "mortgage", true, true);
    Brain target = brain(newId, "lending", false, true);
    when(brains.findById(newId)).thenReturn(Optional.of(target));
    when(brains.findDefaultBrain()).thenReturn(Optional.of(old));
    when(brains.saveAndFlush(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
    when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

    BrainAdminController.BrainDto dto = controller.activate(newId);

    assertEquals(true, dto.isDefault());
    InOrder order = inOrder(brains);
    order.verify(brains).saveAndFlush(old);   // old cleared + flushed first
    order.verify(brains).save(target);        // then new set
    assertFalse(old.isDefault());
    assertEquals(true, target.isDefault());
}

@Test
void activateRejectsInactiveBrain() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, false)));
    assertThrows(IllegalArgumentException.class, () -> controller.activate(id));
}
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement activate:**
```java
import org.springframework.transaction.annotation.Transactional;

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
```

- [ ] **Step 4: Write the Testcontainers invariant test** `BrainAdminActivateInvariantTest.java` — mirror `BrainRepositoryTest`'s container setup; build the controller with the real `BrainRepository` and mocks for the rest (`mock(SyncService.class)`, `mock(DomainPackRegistry.class)`, `mock(ModelRouterService.class)`); the seeded default (`TestBrains.DEFAULT_ID`) exists from V7. Save a second active non-default brain, call `controller.activate(secondId)`, and assert exactly one default remains and it is the second brain — **no `DataIntegrityViolationException`** (proves ordering respects the index against a real Postgres).
```java
package com.msfg.rag.controller;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainAdminActivateInvariantTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Test
    void activateFlipsTheDefaultWithoutViolatingTheUniqueIndex() {
        BrainAdminController controller = new BrainAdminController(
                brains, mock(SyncService.class), mock(DomainPackRegistry.class), mock(ModelRouterService.class));

        UUID secondId = UUID.randomUUID();
        Brain second = new Brain(secondId, "lending", "Lending Brain");
        second.setActive(true);
        second.setDefault(false);
        brains.saveAndFlush(second);

        controller.activate(secondId);

        assertEquals(secondId, brains.findDefaultBrain().orElseThrow().getId());
        assertTrue(brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isActive());
        assertTrue(!brains.findById(TestBrains.DEFAULT_ID).orElseThrow().isDefault());
    }
}
```
*(Note: `@DataJpaTest` wraps each test in a transaction that rolls back; `saveAndFlush` inside `activate` flushes within that transaction so the index is exercised. If the rollback-vs-flush interaction proves flaky, annotate the test method `@org.springframework.transaction.annotation.Transactional(propagation = NOT_SUPPORTED)` so the controller's own `@Transactional` governs — decide at GREEN time, keep whichever is green.)*

- [ ] **Step 5: Run → PASS** (both tests), then full `./gradlew test`.

- [ ] **Step 6: Commit** (heredoc):
```
Phase 6: activate brain admin API (single-default flip)

POST /api/ai/admin/brains/{id}/activate sets is_default=true and clears the
previous default in one transaction, clearing the old default first so the
ux_brains_single_default partial index is never violated. Refuses to activate
an inactive brain. Testcontainers test proves the flip against real Postgres.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 5: Sync — `POST /api/ai/admin/brains/{id}/sync` (TDD)

**Files:**
- Modify: `BrainAdminController.java`
- Modify test: `BrainAdminControllerTest.java`

Behavior: resolve the brain by id (404-style `IllegalArgumentException` if unknown — actually `SyncService` already throws `Unknown brain` for an unknown id, but we check existence first for a clean message and to keep id-keyed semantics), then `return syncService.sync(dryRun, id)`. `dryRun` is a `@RequestParam(defaultValue="false")` exactly like `DocumentAdminController.sync`.

- [ ] **Step 1: Write failing tests** (mirror `DocumentAdminControllerSyncTest`):
```java
import com.msfg.rag.service.sync.SyncReport;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@Test
void syncPassesDryRunThroughByBrainId() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
    SyncReport report = new SyncReport(true, java.util.Map.of("skip", 1), java.util.List.of());
    when(syncService.sync(eq(true), eq(id))).thenReturn(report);

    assertEquals(report, controller.sync(id, true));
    verify(syncService).sync(eq(true), eq(id));
}

@Test
void syncDefaultsToExecute() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
    SyncReport report = new SyncReport(false, java.util.Map.of(), java.util.List.of());
    when(syncService.sync(eq(false), eq(id))).thenReturn(report);

    assertEquals(report, controller.sync(id, false));
}

@Test
void syncRejectsUnknownBrain() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> controller.sync(id, false));
}
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement sync:**
```java
import com.msfg.rag.service.sync.SyncReport;
import org.springframework.web.bind.annotation.RequestParam;

@PostMapping("/{id}/sync")
public SyncReport sync(@PathVariable UUID id,
                       @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
    if (brains.findById(id).isEmpty()) {
        throw new IllegalArgumentException("Unknown brain: " + id);
    }
    return syncService.sync(dryRun, id);
}
```

- [ ] **Step 4: Run → PASS**, then full `./gradlew test`.

- [ ] **Step 5: Commit** (heredoc):
```
Phase 6: per-brain sync admin API (POST /api/ai/admin/brains/{id}/sync)

Id-keyed Sync now for a brain (dryRun optional, default execute) delegating to
SyncService.sync(dryRun, brainId); returns the SyncReport. Keeps all brain
admin actions on one id-keyed surface.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 6 (optional): Soft-delete — `DELETE /api/ai/admin/brains/{id}` (TDD)

**Files:**
- Modify: `BrainAdminController.java`
- Modify: `src/main/java/com/msfg/rag/config/CorsConfig.java` (add `"DELETE"` to the `/api/ai/admin/**` mapping's `allowedMethods`)
- Modify test: `BrainAdminControllerTest.java`

Behavior: soft-delete by setting `is_active=false`; **never** hard-delete; **refuse to delete the default brain** (`IllegalArgumentException`). Return the updated `BrainDto` (now inactive). This is the only thing that touches CORS in this phase.

- [ ] **Step 1: Write failing tests:**
```java
@Test
void deleteSoftDeletesByDeactivating() {
    UUID id = UUID.randomUUID();
    Brain b = brain(id, "lending", false, true);
    when(brains.findById(id)).thenReturn(Optional.of(b));
    when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
    assertEquals(false, controller.softDelete(id).isActive());
    assertFalse(b.isActive());
}

@Test
void deleteRefusesTheDefaultBrain() {
    UUID id = UUID.randomUUID();
    when(brains.findById(id)).thenReturn(Optional.of(brain(id, "mortgage", true, true)));
    assertThrows(IllegalArgumentException.class, () -> controller.softDelete(id));
}
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement + CORS:**
```java
import org.springframework.web.bind.annotation.DeleteMapping;

@DeleteMapping("/{id}")
public BrainDto softDelete(@PathVariable UUID id) {
    Brain brain = brains.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
    if (brain.isDefault()) {
        throw new IllegalArgumentException("Cannot delete the default brain; activate another brain first");
    }
    brain.setActive(false);          // soft delete — never hard-delete
    return BrainDto.from(brains.save(brain));
}
```
In `CorsConfig.addCorsMappings`, change the admin mapping line to:
```java
registry.addMapping("/api/ai/admin/**")
        .allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "PUT", "POST", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "X-Admin-Api-Key")
        .maxAge(3600);
```
*(If you skip Task 6, the frontend (Task 9) must NOT render a delete control — keep them consistent. Recommended: include Task 6; the UI's soft-delete is a low-risk admin nicety.)*

- [ ] **Step 4: Run → PASS**, then full `./gradlew test`.

- [ ] **Step 5: Commit** (heredoc):
```
Phase 6: soft-delete brain admin API (DELETE /api/ai/admin/brains/{id})

Deactivates the brain (never hard-deletes); refuses to delete the default
brain. Adds DELETE to the admin CORS mapping.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 7: Frontend types + api.ts methods (build-green)

**Files:**
- Modify: `dashboard/src/types.ts`
- Modify: `dashboard/src/api.ts`
- Modify: `dashboard/src/api.test.ts` (add a method-path/verb test)

- [ ] **Step 1: Add types** to `dashboard/src/types.ts` (append). The DTO field names match the backend `BrainDto` record exactly (camelCase JSON via Jackson default):
```ts
export interface BrainAdminDto {
  id: string;
  slug: string;
  displayName: string;
  packRef: string | null;
  sourceType: string | null;       // "local" | "s3" | null
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string | null;
  answerModel: string | null;
  utilityProvider: string | null;
  utilityModel: string | null;
  isDefault: boolean;
  isActive: boolean;
}

export interface BrainCreateRequest {
  slug: string;
  displayName: string;
  packRef: string;
  sourceType: "local" | "s3";
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string;
  answerModel: string;
  utilityProvider: string;
  utilityModel: string;
}
```

- [ ] **Step 2: Add api methods** to `dashboard/src/api.ts`. Extend the exported `api` object with brain-scoped helpers (they reuse the same `request`/admin-key path as every other call — no new fetch logic):
```ts
import { BrainAdminDto, BrainCreateRequest, SyncReport } from "./types";

// ...existing `api` object stays; add these methods to it:
export const brainsApi = {
  list: () => api.get<BrainAdminDto[]>("/api/ai/admin/brains"),
  create: (body: BrainCreateRequest) => api.post<BrainAdminDto>("/api/ai/admin/brains", body),
  update: (id: string, body: BrainCreateRequest) =>
    api.put<BrainAdminDto>(`/api/ai/admin/brains/${id}`, body),
  activate: (id: string) => api.post<BrainAdminDto>(`/api/ai/admin/brains/${id}/activate`),
  sync: (id: string, dryRun: boolean) =>
    api.post<SyncReport>(`/api/ai/admin/brains/${id}/sync?dryRun=${dryRun}`),
  remove: (id: string) => api.del<BrainAdminDto>(`/api/ai/admin/brains/${id}`),
};
```
This requires a `del` verb on `api` — add it next to `put` (the existing `request` already handles method/headers/JSON for us):
```ts
export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, form: FormData) =>
    request<T>(path, { method: "POST", body: form }),
};
```
*(Place the `import { BrainAdminDto, ... }` at the top of `api.ts`. If Task 6 was skipped, omit `remove`/`del`.)*

- [ ] **Step 3: Add an api-client test** to `dashboard/src/api.test.ts` (append inside the `describe`), mirroring `fetchReturning`:
```ts
it("posts to the per-brain activate endpoint by id", async () => {
  adminKey.set("k");
  const fetchMock = fetchReturning(200, { id: "abc", slug: "lending", isDefault: true });
  vi.stubGlobal("fetch", fetchMock);

  await brainsApi.activate("abc");

  const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
  expect(call[0]).toBe("/api/ai/admin/brains/abc/activate");
  expect((call[1].method ?? "GET")).toBe("POST");
});
```
Add `brainsApi` to the import line at the top of `api.test.ts`.

- [ ] **Step 4: Green:** `cd /Users/zacharyzink/rag-brain/dashboard && npm run check && npm test -- --run`.

- [ ] **Step 5: Commit** (heredoc):
```
Phase 6: dashboard api client for brains CRUD

Adds BrainAdminDto/BrainCreateRequest types and brainsApi (list/create/update/
activate/sync/remove) reusing the existing admin-key fetch path; adds a `del`
verb. api.test covers the id-keyed activate path + verb.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 8: `Brains` screen — list + per-row Set active / Sync now (build-green)

**Files:**
- Create: `dashboard/src/screens/Brains.tsx`

This task builds the **read + per-row actions** half (mirrors `Corpus.tsx`'s table + `busy`-id + sync-report patterns). The create form is Task 9 (same file, second commit) to keep each step small. Provider options come from the existing `GET /api/ai/admin/settings` `providers` block (reused in Task 9); Task 8 needs only brains + sync.

- [ ] **Step 1: Implement the screen (list + actions).**
```tsx
import { useCallback, useEffect, useState } from "react";
import { brainsApi } from "../api";
import { BrainAdminDto, SyncReport } from "../types";
import { ErrorNote, Pill } from "../components";

function sourceSummary(b: BrainAdminDto): string {
  if (b.sourceType === "local") return `local: ${b.localPath ?? "—"}`;
  if (b.sourceType === "s3") return `s3: ${b.s3Bucket ?? "—"}${b.s3Prefix ? "/" + b.s3Prefix : ""}`;
  return "—";
}

export default function Brains() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    brainsApi.list().then(setBrains).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

  async function setActive(b: BrainAdminDto) {
    setBusy(b.id); setError(null);
    try { await brainsApi.activate(b.id); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function sync(b: BrainAdminDto) {
    setBusy(b.id); setError(null); setReport(null);
    try { setReport(await brainsApi.sync(b.id, false)); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const summaryTone = (k: string): "green" | "amber" | "gray" =>
    k === "upload" || k === "update" ? "green" : k === "deactivate" ? "amber" : "gray";

  return (
    <>
      <header className="screen-head">
        <h1>Brains</h1>
        <span className="muted">create a brain, point it at a folder or bucket, sync, then set active</span>
      </header>
      <ErrorNote message={error} />
      {report && (
        <div className="card sync-report">
          <div className="sync-summary">
            <strong>Sync finished</strong>
            {Object.entries(report.summary).map(([k, v]) => (
              <Pill key={k} tone={summaryTone(k)}>{v} {k}</Pill>
            ))}
          </div>
          {report.results.filter((r) => r.action !== "SKIP" || r.error).map((r) => (
            <div key={`${r.action}-${r.fileName}`} className="diff-line">
              <code>{r.action.toLowerCase()}</code>
              <span>{r.fileName}{r.reason ? ` — ${r.reason}` : ""}{r.error ? ` — FAILED: ${r.error}` : ""}</span>
            </div>
          ))}
        </div>
      )}
      <table className="tbl">
        <thead>
          <tr><th>Brain</th><th>Slug</th><th>Source</th><th>Answer model</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {brains.map((b) => (
            <tr key={b.id}>
              <td>{b.displayName}</td>
              <td><code>{b.slug}</code></td>
              <td>{sourceSummary(b)}</td>
              <td>{b.answerProvider ?? "—"}{b.answerModel ? ` / ${b.answerModel}` : ""}</td>
              <td>
                {b.isDefault && <Pill tone="green">active</Pill>}
                {!b.isActive && <Pill tone="gray">disabled</Pill>}
                {b.isActive && !b.isDefault && <Pill tone="gray">idle</Pill>}
              </td>
              <td className="row-actions">
                <button onClick={() => sync(b)} disabled={busy === b.id || !b.isActive}>
                  {busy === b.id ? "Working…" : "Sync now"}
                </button>
                <button onClick={() => setActive(b)} disabled={busy === b.id || b.isDefault || !b.isActive}>
                  Set active
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
```
*(Uses only existing CSS classes: `screen-head`, `card sync-report`, `sync-summary`, `diff-line`, `tbl`, `row-actions`, `muted` — all present in `styles.css` and used by `Corpus.tsx`. `Pill` tones used are within the allowed union.)*

- [ ] **Step 2: Green:** `npm run check && npm run build` (no new test required for the screen — the project has no React component test harness; `api.test.ts` covers the client). Confirm the existing `npm test -- --run` still passes.

- [ ] **Step 3: Commit** (heredoc):
```
Phase 6: Brains screen — list + per-row Set active / Sync now

Mirrors Corpus.tsx (table + busy-id + sync-report). Set active and Sync now
call brainsApi by id; renders the SyncReport. Create form lands next.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 9: `Brains` screen — Create brain form + nav registration (build-green)

**Files:**
- Modify: `dashboard/src/screens/Brains.tsx` (add the create form)
- Modify: `dashboard/src/App.tsx` (import + NavLink + Route)

The form mirrors `Settings.tsx`'s provider `<select>` (options from the reused settings `providers` block, filtered to `configured`) and free-text model inputs; the source-type toggle swaps between a local-path input and S3 bucket/prefix/region inputs. On submit → `brainsApi.create` → on success refresh the list and clear the form.

- [ ] **Step 1: Add the create form to `Brains.tsx`.** Add imports + state at the top of the component and the form JSX above the table. Fetch the providers block once via the existing settings endpoint:
```tsx
import { api, brainsApi } from "../api";
import { BrainAdminDto, BrainCreateRequest, SettingsResponse, SyncReport } from "../types";
```
Inside the component, add:
```tsx
  const [providers, setProviders] = useState<string[]>([]);
  const blankForm: BrainCreateRequest = {
    slug: "", displayName: "", packRef: "packs/msfg-mortgage", sourceType: "local",
    s3Bucket: null, s3Prefix: null, s3Region: null, localPath: "",
    answerProvider: "anthropic", answerModel: "", utilityProvider: "openai", utilityModel: "",
  };
  const [form, setForm] = useState<BrainCreateRequest>(blankForm);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    api.get<SettingsResponse>("/api/ai/admin/settings")
      .then((s) => setProviders(s.providers.filter((p) => p.configured).map((p) => p.name)))
      .catch(() => setProviders([]));
  }, []);

  function set<K extends keyof BrainCreateRequest>(k: K, v: BrainCreateRequest[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  async function create() {
    setCreating(true); setError(null);
    try {
      await brainsApi.create(form);
      setForm(blankForm);
      reload();
    } catch (e) { setError((e as Error).message); }
    finally { setCreating(false); }
  }

  const providerOptions = (current: string) =>
    providers.includes(current) || !current ? providers : [current, ...providers];
```
Form JSX (place inside the fragment, after `<ErrorNote .../>` and before the sync-report block):
```tsx
      <div className="card">
        <h2>Create brain</h2>
        <div className="setting-row">
          <label>Display name</label>
          <input value={form.displayName} onChange={(e) => set("displayName", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Slug (lowercase, a–z 0–9 -)</label>
          <input value={form.slug} onChange={(e) => set("slug", e.target.value)}
                 placeholder="lending" />
        </div>
        <div className="setting-row">
          <label>Pack ref</label>
          <input value={form.packRef} onChange={(e) => set("packRef", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Source</label>
          <div className="mode-toggle">
            <button className={form.sourceType === "local" ? "on" : ""}
                    onClick={() => set("sourceType", "local")}>Local folder</button>
            <button className={form.sourceType === "s3" ? "on" : ""}
                    onClick={() => set("sourceType", "s3")}>S3</button>
          </div>
        </div>
        {form.sourceType === "local" ? (
          <div className="setting-row">
            <label>Folder path</label>
            <input value={form.localPath ?? ""} onChange={(e) => set("localPath", e.target.value)}
                   placeholder="/Users/you/corpora/lending" />
          </div>
        ) : (
          <>
            <div className="setting-row">
              <label>S3 bucket</label>
              <input value={form.s3Bucket ?? ""} onChange={(e) => set("s3Bucket", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 prefix</label>
              <input value={form.s3Prefix ?? ""} onChange={(e) => set("s3Prefix", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 region</label>
              <input value={form.s3Region ?? ""} onChange={(e) => set("s3Region", e.target.value)} />
            </div>
          </>
        )}
        <div className="setting-row">
          <label>Answer provider</label>
          <select value={form.answerProvider} onChange={(e) => set("answerProvider", e.target.value)}>
            {providerOptions(form.answerProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Answer model</label>
          <input value={form.answerModel} onChange={(e) => set("answerModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        <div className="setting-row">
          <label>Utility provider</label>
          <select value={form.utilityProvider} onChange={(e) => set("utilityProvider", e.target.value)}>
            {providerOptions(form.utilityProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Utility model</label>
          <input value={form.utilityModel} onChange={(e) => set("utilityModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={create}
                  disabled={creating || !form.slug.trim() || !form.displayName.trim()
                            || (form.sourceType === "local" ? !form.localPath?.trim() : !form.s3Bucket?.trim())}>
            {creating ? "Creating…" : "Create brain"}
          </button>
        </div>
      </div>
```
*(`mode-toggle`, `setting-row`, `btn-primary`, `card` all exist in `styles.css` — `mode-toggle` is used by `TestConsole.tsx`, `setting-row` by `Settings.tsx`.)*

- [ ] **Step 2: Register the screen in `App.tsx`.** Three precise edits:
  - **After line 5** (`import Corpus from "./screens/Corpus";`) add:
    ```tsx
    import Brains from "./screens/Brains";
    ```
  - **In the `<nav className="nav">` block (after the `/corpus` NavLink, currently line 61)** add a NavLink so Brains sits first-after-Corpus:
    ```tsx
    <NavLink to="/brains">Brains</NavLink>
    ```
  - **In `<Routes>` (after the `/corpus` Route, currently lines 73–74)** add:
    ```tsx
    <Route path="/brains" element={<Brains />} />
    ```
  (Leave the catch-all `<Route path="*" element={<Navigate to="/corpus" replace />} />` as-is — Corpus stays the landing screen.)

- [ ] **Step 3: Green:** `npm run check && npm test -- --run && npm run build` → all green.

- [ ] **Step 4: Commit** (heredoc):
```
Phase 6: Brains screen — Create brain form + nav registration

Create form mirrors Settings (provider selects from the configured-providers
block) with a local/S3 source toggle; submit -> brainsApi.create then refresh.
Registers the Brains screen in App nav + routes.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 10: Full regression + manual end-to-end on :8090

- [ ] **Step 1: Backend full suite:** `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL` (golden/compliance/pack tests unchanged).
- [ ] **Step 2: Frontend:** `cd dashboard && npm run check && npm test -- --run && npm run build` → all green.
- [ ] **Step 3: Manual E2E (the headline flow).** Boot the app on **8090** (per the project memory: 8080 collides; use `--server.port=8090`) with the DB up. Unlock the dashboard with the admin key, open **Brains**:
  1. Create a brain: display name + a URL-safe slug + `packRef` pointing at a pack whose `pack.yaml` slug equals that slug + **Local folder** with a real folder path containing a supported file + providers/models. Submit → it appears in the list (`idle`).
  2. **Sync now** on that row → a `SyncReport` renders (a real folder with a file → an `upload`/`update`; dry run not needed for the manual check).
  3. **Set active** → it becomes `active` and the previously-active brain loses its badge; refetch confirms exactly one `active`.
  4. Negatives: creating with a duplicate slug, a non-URL-safe slug, a `packRef` whose pack slug mismatches, or a local source with a blank path each shows the server's 400 message via `ErrorNote`.
  Stop the app; `docker compose down` if used.
- [ ] **Step 4: Isolation gate:** `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only its own pre-existing `?? scripts/` (we never touched it). All commits landed in `/Users/zacharyzink/rag-brain`.

---

## Self-Review

- **Spec coverage:**
  - `GET /api/ai/admin/brains` (list, non-secret DTO) — Task 1. `GET /{id}` — Task 1.
  - `POST /api/ai/admin/brains` (create; slug unique + `^[a-z0-9-]+$`; pack loads + slug matches; source binding present; id generated; no `is_default`) — Task 2.
  - `PUT /api/ai/admin/brains/{id}` (update configurable fields; re-validate; `registry.reload(id)` on pack change) — Task 3.
  - `POST /api/ai/admin/brains/{id}/activate` (single-default flip in one transaction, old default cleared first) — Task 4.
  - `POST /api/ai/admin/brains/{id}/sync?dryRun=` (delegates to `SyncService.sync(dryRun, id)`, returns `SyncReport`) — Task 5.
  - `DELETE /api/ai/admin/brains/{id}` (soft-delete; never the default) — Task 6 (optional, recommended).
  - DTOs as controller-local records matching `AdminStatsController` style — all tasks.
  - 401 proof: `AdminApiKeyFilterTest` asserts `/api/ai/admin/brains` is gated — Task 1, Step 4.
  - Frontend: `api.ts` `list/create/update/activate/sync/remove`; `Brains` screen (table + Set active + Sync now + Create form with source toggle + provider selects); nav registration — Tasks 7–9.
- **Placeholder scan:** none. Every new file is given in full (controller, both backend tests, both frontend types, api methods, the screen) and every modification is an exact edit (the `AdminApiKeyFilterTest` line, the three `App.tsx` edits with current line anchors, the `CorsConfig` method-list line). No "similar to above."
- **Type/route consistency:** backend `BrainDto`/`CreateBrainRequest`/`UpdateBrainRequest` field names ↔ frontend `BrainAdminDto`/`BrainCreateRequest` (camelCase, Jackson default) line up 1:1; routes are exactly `/api/ai/admin/brains`, `/{id}`, `/{id}/activate`, `/{id}/sync`, `/{id}` (DELETE); the frontend calls those exact paths. `SyncReport` shape already exists in `types.ts`.
- **Secret-exposure check:** `BrainDto` has **no** `localApiKeyRef`/`localBaseUrl` field and no accessor for them; create/update request records have **no** secret fields and `apply(...)` never sets them; a unit assertion (`!dto.toString().contains("secret-ref")`) guards against accidental leakage. The two secret/SSRF columns are explicitly out of scope (below).
- **Error altitude:** every admin-fixable failure throws `IllegalArgumentException` → clean 400 (verified against `GlobalExceptionHandler`); we deliberately validate the pack via `DomainPackLoader` rather than `DomainPackRegistry.bundle()` to avoid the registry's `IllegalStateException` → 500.
- **Index safety:** activate clears the old default with `saveAndFlush` before setting the new one, inside `@Transactional`; a Testcontainers test proves it against the real `ux_brains_single_default` partial index.
- **Build-green frontend:** `npm run check` + `npm test -- --run` + `npm run build` are the gates; only existing CSS classes and `components.tsx` primitives are used; no new state/styling library introduced.

## Deferred (out of scope for this MVP — note for a later phase)

- **Per-brain local LLM endpoint + key (P4b-2 + SSRF):** `local_base_url` / `local_api_key` are NOT settable in the create/update form and NOT in any DTO. Wiring a per-brain local endpoint safely requires SSRF egress controls and secret handling — a dedicated phase.
- **YAML export/import** of a brain's config/pack — deferred.
- **`rule_revisions` per brain** surfaced in this screen — deferred (the Rules screen is brain-agnostic today).
- **Brain selector on every screen** (Corpus/Settings/Rules/Test console/Audit scoped by the active/selected brain via `?brain=`) — deferred; this phase manages brains but the other screens still operate on the default brain.
- **Full secret-redaction hardening** (audit-log/response redaction passes for any brain field) — deferred.
- **Cognito/JWT auth** replacing the static `X-Admin-Api-Key` gate — tracked separately (the filter already notes it as an interim measure).
