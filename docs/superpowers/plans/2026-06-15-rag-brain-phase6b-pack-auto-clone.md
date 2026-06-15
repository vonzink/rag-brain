# rag-brain Phase 6b — Frictionless Brain Create (Loader Relaxation + Pack Auto-Clone) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax. Backend tasks are TDD (write the failing test first, watch it fail, then make it pass); frontend tasks are build-green (`npm run check` + `npm test`). Each task ends green with a single commit. All work happens in `/Users/zacharyzink/rag-brain`; **never read, touch, or reference `/Users/zacharyzink/MSFG/msfg-rag`.**

**Goal:** Make "connect a folder" frictionless. Today, creating a brain requires a pre-authored pack whose `pack.yaml` slug equals the brain slug (`BrainAdminController.validatePack` 400s otherwise) AND whose `retrieval.yaml` carries mortgage-specific acronyms + programs (`DomainPackLoader.validate()` requires both non-empty). After this phase: (1) a generic pack needs **no** acronyms/programs — they become optional; (2) creating a brain with **no** `packRef` auto-generates a slug-matched **neutral starter pack** from a committed `packs/_template/`, so the user never hand-authors one. Creating with an explicit `packRef` keeps the existing, unchanged validation path.

**Architecture:** Two parts, sequenced. **Part 1 (loader relaxation):** in `DomainPackLoader`, drop the two `require(... acronyms ...)` / `require(... programs ...)` non-empty checks so empty/absent acronyms+programs is valid; in `load()`, normalize null acronyms→`Map.of()` and null programs→`List.of()` before constructing the `DomainPack`, so the downstream `BrainPackBundle.of` → `CompiledProgram.compile(rules)` (which calls `rules.stream()`) never NPEs on a program-less pack. Per-element validation (the `for` loops, lowercase/blank checks) stays and runs only when present. **Part 2 (template + auto-clone):** add a committed `packs/_template/` (5 neutral, placeholder files — compliance-reviewed verbatim, never loaded directly); add a `PackTemplateService` that clones `_template` to `packs/<slug>`, writes a YAML-safe `pack.yaml` with the real slug/company/disclaimer, and validates the result via `DomainPackLoader.load` (fail-fast, delete partial dir on failure); wire `BrainAdminController.create` so a blank `packRef` calls the service and uses the returned `packs/<slug>` ref. Add an optional `disclaimer` field to the create DTO (used only when generating). Frontend: the create form gets a "Generate a starter pack (recommended)" vs "Use an existing pack" toggle.

**Tech Stack:** Java 21 · Spring Boot · Jackson `YAMLMapper` (already a dependency, used by `DomainPackLoader`) · JUnit 5 + Mockito (controller + loader + service unit tests, `@TempDir` for filesystem) · React 18 + TypeScript + Vite · Vitest.

**Sequencing (hard requirement):** Part 1 lands fully green BEFORE Part 1's template/Part 2 — the neutral template's `retrieval.yaml` is `acronyms: {}` / `programs: []`, which is only valid once the loader is relaxed. Backend (Tasks 1–5) before frontend (Task 6).

---

## Context (verified — real signatures/snippets read from the repo at this SHA)

Repo pinned at `11b1f375e0db2b0a7a805e937294e8b49639aa9b` (branch `main`).

### The exact lines to change in `DomainPackLoader.validate()`
`src/main/java/com/msfg/rag/pack/DomainPackLoader.java`, lines 157–164 (current):
```java
        require(dir, "retrieval.yaml", "acronyms",
                p.acronymExpansions() != null && !p.acronymExpansions().isEmpty());
        require(dir, "retrieval.yaml", "programs",
                p.programRules() != null && !p.programRules().isEmpty());
        for (DomainPack.ProgramRule rule : p.programRules()) {
            require(dir, "retrieval.yaml", "programs.program", notBlank(rule.program()));
            compileAll(dir, "retrieval.yaml", rule.wordPatterns());
        }
```
The two `require(...)` calls make acronyms+programs mandatory. The `for` loop validates each program (already null-safe once programs is `List.of()`). **Everything else in `validate()` stays required** (lines 114–155): slug `[a-z0-9-]+`, company-name, disclaimer, prompt template with exactly 5 `%s` (`split("%s", -1).length == 6`) + valid format string, hard-rules, guidance, prohibited-phrases non-empty, eligible-phrase, all six canned answers (`no-source`, `escalation`, `legal`, `tax`, `live-rates`, `fraud`), classifier rules non-empty with ≥1 pattern each + no duplicate category + compilable regex.

### The null-safety lines to change in `DomainPackLoader.load()`
`load()` lines 100–106 (current) pass acronyms/programs straight through (null stays null):
```java
                retrievalFile.acronyms(),
                retrievalFile.programs() == null ? null : retrievalFile.programs().stream()
                        .map(p -> new DomainPack.ProgramRule(
                                p.program(),
                                p.keywords() == null ? List.of() : p.keywords(),
                                p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                        .toList());
```
Lines 64–84 already do per-element checks *guarded by* `if (... != null)`, so an absent `acronyms:`/`programs:` key (→ null) skips them — fine. **The fix:** normalize to empty collections at the `DomainPack` boundary (acronyms→`java.util.Map.of()`, programs→`java.util.List.of()`) so downstream consumers never see null.

### Why null→empty is load-bearing (NPE proof)
`pack/BrainPackBundle.java` `of(DomainPack pack)` builds per-brain derived state:
```java
        return new BrainPackBundle(
                pack,
                Collections.unmodifiableMap(compiled),
                CompiledProgram.compile(pack.programRules()),   // <-- programRules()
                pack.acronymExpansions());                       // <-- acronymExpansions()
```
`pack/CompiledProgram.java` `compile(...)`:
```java
    public static List<CompiledProgram> compile(List<DomainPack.ProgramRule> rules) {
        return rules.stream()                                   // <-- NPE if null
                .map(r -> new CompiledProgram(...))
                .toList();
    }
```
If `programRules()` were null, `CompiledProgram.compile` NPEs the moment any brain's bundle is built (every retrieval call). `DomainPack`'s compact ctor copies a non-null list with `List.copyOf(...)` (line 30) and `Map.copyOf(...)` (line 29), so passing `List.of()`/`Map.of()` is safe and stays immutable. Passing empty collections from `load()` is exactly what prevents the NPE.

### `RetrievalService` handles empty maps/lists gracefully (verified)
`service/retrieval/RetrievalService.java`:
- `expandQuery(question, acronyms)` (lines 202–220): tokenizes, calls `acronyms.get(token)`; an empty map returns `null` for every token, `expansions` stays empty, returns the question unchanged. ✔ No NPE, no behavior change.
- `detectPrograms(text, programs)` (lines 244–258): `for (CompiledProgram program : programs)` over an empty list iterates zero times, returns an empty set. ✔ Then `questionPrograms.isEmpty()` is true in `toRetrievedChunk` (line 149) and `programScoreFactor` returns `1.0` (line 267) — i.e. program-aware ranking is a no-op for a program-less pack. Exactly the intended "no acronym expansion / no program-aware ranking" semantics.

`pack/BrainPackBundleTest.java` already asserts `bundle.acronyms()` equals `pack.acronymExpansions()` and lists program names for the MSFG pack — empty-pack behavior is new test surface (Task 1).

### The two real packs that must STILL load (they HAVE acronyms+programs)
- `packs/msfg-mortgage/` (committed; tracked in git) — 14 acronyms, 4 programs. Locked by `pack/MsfgGoldenPackTest.java` (`retrievalRulesMatchLegacy` asserts the full 14-entry acronym map + 4 ordered programs). Must stay byte-green.
- `src/test/resources/packs/test-pack/` (slug `testco`) — 1 acronym (`pmi`), 2 programs (FHA, VA). Locked by `pack/DomainPackLoaderTest.loadsAllFiveFilesIntoOnePack`. Must stay green. (`TestPacks.msfg()` loads `packs/msfg-mortgage` from repo-root working dir; `DomainPackLoaderTest.TEST_PACK = src/test/resources/packs/test-pack`.)

### `BrainAdminController.create` + `validatePack` + DTO (current)
`controller/BrainAdminController.java`:
```java
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
    requireText("packRef", req.packRef());                 // <-- currently mandatory
    requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());
    validatePack(req.packRef().trim(), slug);              // <-- loads + slug-matches

    Brain brain = new Brain(UUID.randomUUID(), slug, req.displayName().trim());
    apply(brain, req.packRef(), req.sourceType(), ...);    // apply() sets packRef via trimToNull
    brain.setDefault(false);
    brain.setActive(true);
    return BrainDto.from(brains.save(brain));
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
```
`validatePack` is package-private and **overridable** — `BrainAdminControllerTest` subclasses the controller with `@Override void validatePack(...) {}` to no-op the filesystem in unit tests. `apply(brain, packRef, ...)` (line 208) does `brain.setPackRef(trimToNull(packRef))`. Error mapping: `IllegalArgumentException` → 400 via `GlobalExceptionHandler` (per the Phase 6 plan context). `update()` keeps requiring `packRef` — **out of scope; do not change update.**

### Controller test style (plain JUnit + Mockito — NO Spring context)
`controller/BrainAdminControllerTest.java` `new`s the controller with mocked `BrainRepository`/`SyncService`/`DomainPackRegistry`/`ModelRouterService`, stubs `brains.findBySlug(...)` / `brains.save(...)`, and uses a `noPackCheck()` subclass (overrides `validatePack` to accept) so non-pack cases never hit disk. `createValidatesPackSlugAgainstRealPack` / `createRejectsPackSlugMismatch` use the **real** on-disk fixture `src/test/resources/packs/test-pack` (slug `testco`) to exercise the actual loader. The `CreateBrainRequest` is constructed positionally (12 args). **Adding a field changes that positional ctor — every `new CreateBrainRequest(...)` in the test file must gain the new trailing arg.** (8 call sites: lines 89, 118, 126, 137, 147, 155 — count and update all.)

### Loader test style (`@TempDir` + fixture copy)
`pack/DomainPackLoaderTest.java`: `packCopy()` copies the 5 fixture files into a `@TempDir`, then a test overwrites one file with `Files.writeString(...)` and asserts `PackValidationException` (or success). New empty-acronyms/programs test (Task 1) follows this exact shape but asserts a **successful** load.

### Packs directory + gitignore (CONFIRMED)
`.gitignore` ignores `.gradle/`, `build/`, `.idea/`, `*.iml`, `.vscode/`, `.env`, **`data/`**, `.DS_Store`, `*.log`. **`packs/` is NOT gitignored.** `git ls-files packs/` returns the 5 `packs/msfg-mortgage/*.yaml` files — so `packs/` is a tracked directory and `packs/_template/*` will be tracked when added. Runtime-generated `packs/<slug>/` dirs would otherwise be committed too — see Decision D5 (`.gitignore` rule for runtime packs).

### Frontend create form (current)
`dashboard/src/screens/Brains.tsx`:
- `blankForm` seeds `packRef: "packs/msfg-mortgage"`, `sourceType: "local"`, etc.
- A `Pack ref` text input bound to `form.packRef` (lines 90–93) and a hint `<p className="muted">The pack's internal slug must equal this brain's slug. Copy packs/msfg-mortgage…</p>` (line 94).
- `Source` toggle (`local`/`s3`) uses the `.mode-toggle` / `button.on` pattern (lines 96–103) — **reuse this exact pattern for the generate/existing toggle.**
- `create()` calls `brainsApi.create(form)`; submit button disabled unless slug+displayName+source are filled.

`dashboard/src/api.ts`: `brainsApi.create = (body: BrainCreateRequest) => api.post<BrainAdminDto>("/api/ai/admin/brains", body)`.

`dashboard/src/types.ts`: `BrainCreateRequest` has required `packRef: string` (line 81) and no `disclaimer`. JSON body is sent as-is; a missing key would just be absent from JSON (which the Java DTO reads as null).

---

## Decisions / ambiguities resolved

- **D1 — `PackTemplateService` package.** Place in `com.msfg.rag.pack` (next to `DomainPackLoader`, which it depends on). It is pack-domain logic, not a request service; keeping it in `pack` avoids a `service → pack` import asymmetry and matches where `DomainPackLoader`/`DomainPackRegistry` live.
- **D2 — Safe `pack.yaml` write.** Do NOT string-replace `__SLUG__`/`__COMPANY_NAME__`/`__DISCLAIMER__` (a company name with `:` or quotes breaks YAML). Serialize a `LinkedHashMap<String,String>` of `{slug, company-name, disclaimer}` with a Jackson `YAMLMapper` (same dependency `DomainPackLoader` uses) configured `MINIMIZE_QUOTES` off / default — Jackson YAML quotes/escapes values correctly. The loader uses KEBAB_CASE deserialization, so write literal kebab keys (`company-name`). The 4 non-identity template files are copied **verbatim** (no placeholders inside them), so only `pack.yaml` is generated.
- **D3 — Generated pack target.** `packs/<slug>` relative to the working dir (resolved to absolute for the loader). This is fine for the local/personal deployment this repo targets. A read-only bundled deploy (jar with a read-only `packs/` on the classpath) would need a configurable writable packs dir — **Deferred** (see below), noted inline in the service Javadoc.
- **D4 — Already-exists handling.** If `packs/<slug>` already exists, `PackTemplateService.generate` throws `IllegalArgumentException("pack already exists at packs/" + slug)`. In `create`, the slug-uniqueness check (`brains.findBySlug`) runs first, so an existing brain is rejected before we get here; a stray on-disk `packs/<slug>` with no brain is a real conflict and a clean 400 is correct.
- **D5 — gitignore for runtime packs.** Add to `.gitignore`:
  ```gitignore
  # Runtime-generated brain packs (keep the committed ones)
  /packs/*
  !/packs/msfg-mortgage/
  !/packs/_template/
  ```
  This keeps `packs/msfg-mortgage` and `packs/_template` tracked while leaving any `packs/<slug>` a user generates untracked (it is per-deployment data, like `data/`). Leading `/` anchors to repo root so `src/test/resources/packs/*` is unaffected.
- **D6 — Default disclaimer when generating.** If the create request's `disclaimer` is blank/absent, use `"Educational use only — verify against the source documents."`. Only used in the generate path; never a secret.
- **D7 — `create` keeps `validatePack` overridable + reused.** After generating, set `req`'s effective packRef to `packs/<slug>` and run the SAME post-create flow. Because the generated pack's slug == brain slug by construction, calling `validatePack(generatedRef, slug)` passes — but to keep unit tests filesystem-free we route the generate branch through the injected service (mockable) and only call `validatePack` on the explicit-packRef branch (mirrors today). The generated pack is already validated inside `generate()` via `DomainPackLoader.load`, so re-validating would be redundant; we skip the second load for the generate branch.
- **D8 — `BrainAdminController` constructor gains `PackTemplateService`.** Every `new BrainAdminController(...)` in tests must add the new arg (a mock). 5 call sites in `BrainAdminControllerTest` (lines 27, 79, 172, 190, plus the field at 27). Count and update all.

---

## File structure

### New files
| Path | Purpose |
|---|---|
| `packs/_template/pack.yaml` | Neutral starter identity (placeholders; pack.yaml is regenerated, not copied). |
| `packs/_template/prompt.yaml` | Neutral prompt template + hard-rules + guidance (copied verbatim). |
| `packs/_template/guardrails.yaml` | Neutral prohibited-phrase, eligible-phrase, 6 canned answers (copied verbatim). |
| `packs/_template/classifier.yaml` | Minimal FRAUD classifier rule (copied verbatim). |
| `packs/_template/retrieval.yaml` | Empty acronyms + programs (`{}` / `[]`) — valid only after Part 1 (copied verbatim). |
| `src/main/java/com/msfg/rag/pack/PackTemplateService.java` | Clones `_template`→`packs/<slug>`, writes safe `pack.yaml`, validates, returns `packs/<slug>`. |
| `src/test/java/com/msfg/rag/pack/PackTemplateServiceTest.java` | Unit tests for generate / already-exists / YAML-safe company name / partial-dir cleanup. |

### Modified files
| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/pack/DomainPackLoader.java` | Drop the two acronyms/programs `require(...)` lines; normalize null→empty in `load()`. |
| `src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java` | Add `emptyAcronymsAndProgramsLoadSuccessfully` test. |
| `src/main/java/com/msfg/rag/controller/BrainAdminController.java` | Inject `PackTemplateService`; add `disclaimer` to `CreateBrainRequest`; branch `create` on blank packRef. |
| `src/test/java/com/msfg/rag/controller/BrainAdminControllerTest.java` | Add `PackTemplateService` mock to ctor (all call sites); add generate-path + existing-path tests; add trailing `disclaimer` arg to every `CreateBrainRequest`. |
| `.gitignore` | Add runtime-pack ignore rules (D5). |
| `dashboard/src/types.ts` | `BrainCreateRequest`: `packRef` stays but add `disclaimer?: string`; add a `generatePack` UI-only flag is NOT persisted — see Task 6. |
| `dashboard/src/screens/Brains.tsx` | Generate/existing toggle; send no `packRef` (omit) when generating; disclaimer input. |

---

## Tasks

### Task 1 — Loader relaxation: acronyms/programs OPTIONAL + null-safe (TDD, backend)

- [ ] **Write the failing test first.** In `DomainPackLoaderTest.java`, add:
  ```java
  @Test
  void emptyAcronymsAndProgramsLoadSuccessfully() throws IOException {
      Path dir = packCopy();
      Files.writeString(dir.resolve("retrieval.yaml"), """
              acronyms: {}
              programs: []
              """);
      DomainPack pack = loader.load(dir);
      assertNotNull(pack.acronymExpansions());
      assertTrue(pack.acronymExpansions().isEmpty(), "empty acronyms must be allowed");
      assertNotNull(pack.programRules());
      assertTrue(pack.programRules().isEmpty(), "empty programs must be allowed");
      // Null-safety: building the per-brain bundle must not NPE (CompiledProgram.compile).
      BrainPackBundle bundle = BrainPackBundle.of(pack);
      assertTrue(bundle.programs().isEmpty());
      assertTrue(bundle.acronyms().isEmpty());
  }
  ```
  Also add a companion test for **absent** keys (the YAML has no `acronyms:`/`programs:` at all):
  ```java
  @Test
  void absentAcronymsAndProgramsLoadSuccessfully() throws IOException {
      Path dir = packCopy();
      Files.writeString(dir.resolve("retrieval.yaml"), "{}\n");
      DomainPack pack = loader.load(dir);
      assertTrue(pack.acronymExpansions().isEmpty());
      assertTrue(pack.programRules().isEmpty());
      assertDoesNotThrow(() -> BrainPackBundle.of(pack));
  }
  ```
  Run `./gradlew test --tests '*DomainPackLoaderTest'` — these MUST fail (currently `require(... acronyms ...)`/`require(... programs ...)` throw, and on absent keys `load()` passes null → `assertTrue(pack.acronymExpansions().isEmpty())` NPEs or the bundle NPEs).

- [ ] **Make it pass — edit `validate()`.** Delete these two blocks (lines 157–160):
  ```java
      require(dir, "retrieval.yaml", "acronyms",
              p.acronymExpansions() != null && !p.acronymExpansions().isEmpty());
      require(dir, "retrieval.yaml", "programs",
              p.programRules() != null && !p.programRules().isEmpty());
  ```
  Leave the program-element `for` loop (lines 161–164) — it now iterates an empty list harmlessly. (The acronym element checks already live in `load()` lines 64–77, guarded by `if (... != null)`.)

- [ ] **Make it pass — edit `load()`.** Change the `DomainPack` construction (lines 100–106) so acronyms and programs are never null:
  ```java
          retrievalFile.acronyms() == null ? java.util.Map.of() : retrievalFile.acronyms(),
          retrievalFile.programs() == null ? java.util.List.of() : retrievalFile.programs().stream()
                  .map(p -> new DomainPack.ProgramRule(
                          p.program(),
                          p.keywords() == null ? List.of() : p.keywords(),
                          p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                  .toList());
  ```

- [ ] **Run the full pack suite green:** `./gradlew test --tests '*pack*'`. `MsfgGoldenPackTest` (14 acronyms / 4 programs) and `DomainPackLoaderTest.loadsAllFiveFilesIntoOnePack` (testco fixture) MUST stay green — they have acronyms+programs, so nothing about their path changed. Confirm `BrainPackBundleTest` green.
- [ ] **Verify before claiming done** (superpowers:verification-before-completion): paste the passing test output for `*pack*`.
- [ ] **Commit:** `Phase 6b: acronyms/programs optional in domain pack loader (null-safe)`.

### Task 2 — Neutral starter template files (`packs/_template/`)

> These 5 files are compliance-reviewed; reproduce **verbatim**. `retrieval.yaml` is only valid because Task 1 relaxed the loader — that is why this task follows Task 1.

- [ ] Create `packs/_template/pack.yaml`:
  ```yaml
  slug: __SLUG__
  company-name: __COMPANY_NAME__
  disclaimer: __DISCLAIMER__
  ```
- [ ] Create `packs/_template/prompt.yaml`:
  ```yaml
  template: |
    You answer strictly from the provided source context. Follow these rules.

    Rules (must follow):
    %s

    Guidance:
    %s

    Source context:
    %s

    Question: %s

    %s
  hard-rules: |-
    - Answer only using the provided source context. Do not use outside knowledge.
    - If the context does not contain the answer, say you could not find it in the provided documents.
    - Cite the source documents you used.
    - Do not provide legal, tax, medical, or financial advice; recommend a qualified professional.
  guidance: |-
    - Be concise and accurate; prefer quoting the source when precision matters.
    - If the question is ambiguous, answer the most likely intent and note the assumption.
  ```
- [ ] Create `packs/_template/guardrails.yaml`:
  ```yaml
  prohibited-phrases:
    - guaranteed
  eligible-phrase: based on the documents
  canned-answers:
    no-source: I could not find that in the provided documents. Please rephrase or add the relevant document.
    escalation: This question is best handled by a qualified professional.
    legal: I can't provide legal advice. Please consult a licensed attorney.
    tax: I can't provide tax advice. Please consult a qualified tax professional.
    live-rates: I can't provide live rates or pricing. Please check an official source.
    fraud: I can't help with that request.
  ```
- [ ] Create `packs/_template/classifier.yaml`:
  ```yaml
  rules:
    - category: FRAUD
      patterns:
        - '\b(fake|forge|forged|falsify|fabricate)\b'
  ```
- [ ] Create `packs/_template/retrieval.yaml`:
  ```yaml
  acronyms: {}
  programs: []
  ```
- [ ] **Sanity-load it (manual, no test yet):** the template is never loaded directly with placeholders (`__SLUG__` is a valid slug shape but a non-real value). Do NOT add a test that loads `_template` verbatim — `__SLUG__` contains underscores and capitals would fail the slug regex (`__slug__` is lowercase but has underscores → fails `[a-z0-9-]+`). Its validity is proven indirectly via Task 3 (which writes a real `pack.yaml`). Note this in the commit body.
- [ ] **Commit:** `Phase 6b: add neutral starter pack template (packs/_template)`.

### Task 3 — `PackTemplateService` (TDD, backend)

- [ ] **Write the failing test first.** `PackTemplateServiceTest.java` (`@TempDir` for an isolated packs root — see below). The service must take the packs-root dir so tests don't write into the repo's real `packs/`. Tests:
  - `generatesValidSlugMatchedPack`: `generate("acme", "Acme Co", "Educational only.")` returns `"packs/acme"`, creates `packs/acme/{pack,prompt,guardrails,classifier,retrieval}.yaml`, and `new DomainPackLoader().load(packsRoot/"acme")` yields a pack with `slug()=="acme"`, `companyName()=="Acme Co"`, `disclaimer()=="Educational only."`, empty acronyms/programs.
  - `companyNameWithColonAndQuotesStaysValidYaml`: `generate("acme2", "Acme: \"The Best\" Co", "Edu")` — load succeeds and `companyName()` round-trips exactly (proves D2 safe serialization, not naive replace).
  - `rejectsWhenTargetExists`: pre-create `packsRoot/acme`, expect `IllegalArgumentException` containing "already exists".
  - `deletesPartialDirOnValidationFailure`: point the service at a `_template` whose `prompt.yaml` is broken (e.g. only 1 `%s`); `generate(...)` throws `IllegalArgumentException` AND `packsRoot/<slug>` does not exist afterward. (Set up a broken template copy in a second `@TempDir`.)
  Run `./gradlew test --tests '*PackTemplateServiceTest'` — fails (class doesn't exist).

- [ ] **Make it pass — write `PackTemplateService`.** Reference implementation:
  ```java
  package com.msfg.rag.pack;

  import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
  import org.springframework.stereotype.Service;

  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.stream.Stream;

  /**
   * Generates a slug-matched neutral starter pack by cloning {@code packs/_template}
   * to {@code packs/<slug>}, writing a YAML-safe {@code pack.yaml}, and validating
   * the result with {@link DomainPackLoader}. Used when a brain is created with no
   * explicit packRef, so "connect a folder" needs no hand-authored pack.
   *
   * <p>Deployment caveat: this writes to the working-dir {@code packs/} tree. That
   * is correct for the local/personal deployment this repo targets; a read-only
   * bundled deploy (jar with a read-only classpath {@code packs/}) would need a
   * configurable writable packs dir — out of scope here (see plan Deferred).
   */
  @Service
  public class PackTemplateService {

      private static final List<String> COPY_FILES =
              List.of("prompt.yaml", "guardrails.yaml", "classifier.yaml", "retrieval.yaml");

      private final Path packsRoot;
      private final Path templateDir;
      private final YAMLMapper yaml = new YAMLMapper();

      /** Production: packs root = ./packs, template = ./packs/_template. */
      public PackTemplateService() {
          this(Path.of("packs"), Path.of("packs", "_template"));
      }

      /** Test/seam ctor: explicit roots so unit tests never touch the real packs/. */
      public PackTemplateService(Path packsRoot, Path templateDir) {
          this.packsRoot = packsRoot;
          this.templateDir = templateDir;
      }

      /** @return the packRef string {@code packs/<slug>} for the generated pack. */
      public String generate(String slug, String companyName, String disclaimer) {
          Path target = packsRoot.resolve(slug);
          if (Files.exists(target)) {
              throw new IllegalArgumentException("pack already exists at packs/" + slug);
          }
          try {
              Files.createDirectories(target);
              for (String f : COPY_FILES) {
                  Files.copy(templateDir.resolve(f), target.resolve(f));
              }
              writePackYaml(target.resolve("pack.yaml"), slug, companyName, disclaimer);
              // Fail-fast: a generated pack that the loader rejects must not survive.
              new DomainPackLoader().load(target.toAbsolutePath().normalize());
          } catch (IOException e) {
              deleteQuietly(target);
              throw new IllegalArgumentException("could not generate pack for slug '" + slug + "': " + e.getMessage(), e);
          } catch (DomainPackLoader.PackValidationException e) {
              deleteQuietly(target);
              throw new IllegalArgumentException("generated pack for slug '" + slug + "' is invalid: " + e.getMessage(), e);
          }
          return "packs/" + slug;
      }

      private void writePackYaml(Path file, String slug, String companyName, String disclaimer) throws IOException {
          Map<String, String> identity = new LinkedHashMap<>();
          identity.put("slug", slug);
          identity.put("company-name", companyName);   // YAMLMapper quotes/escapes ':' and quotes safely
          identity.put("disclaimer", disclaimer);
          yaml.writeValue(file.toFile(), identity);
      }

      private void deleteQuietly(Path dir) {
          if (!Files.exists(dir)) return;
          try (Stream<Path> walk = Files.walk(dir)) {
              walk.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
          } catch (IOException ignored) {
              // best-effort cleanup of the partial dir
          }
      }
  }
  ```
  Notes: `YAMLMapper` writes kebab keys literally (we put `company-name` directly, so no naming strategy needed) and quotes/escapes a value containing `:` or `"` — proving D2. `DomainPackLoader` uses KEBAB_CASE on read; our keys are already kebab, so the round-trip matches.

- [ ] **Run green:** `./gradlew test --tests '*PackTemplateServiceTest'`. Paste output.
- [ ] **Commit:** `Phase 6b: PackTemplateService clones _template into a slug-matched pack`.

### Task 4 — Wire `BrainAdminController.create` (TDD, backend)

- [ ] **Update existing tests for the ctor + DTO change FIRST (red, then green by construction).** In `BrainAdminControllerTest.java`:
  - Add a field `private final PackTemplateService packTemplate = mock(PackTemplateService.class);` and pass it as the new **last** ctor arg in every `new BrainAdminController(...)` (the main controller + the two anonymous-subclass `noPackCheck()`/inline cases). Count: update all 4 instantiations.
  - Add the trailing `disclaimer` arg (`null` for existing cases) to **every** `new CreateBrainRequest(...)` (8 sites). Run `./gradlew test --tests '*BrainAdminControllerTest'` — compile-fails until the production DTO/ctor change lands; that's the red.
- [ ] **Add the new behavioral tests:**
  ```java
  @Test
  void createWithoutPackRefGeneratesPackAndUsesIt() {
      when(brains.findBySlug("acme")).thenReturn(Optional.empty());
      when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
      when(packTemplate.generate(eq("acme"), eq("Acme"), anyString())).thenReturn("packs/acme");

      var req = new BrainAdminController.CreateBrainRequest(
              "acme", "Acme", null /* no packRef */, "local",
              null, null, null, "/corpora/acme",
              "anthropic", "m", "openai", "u", null /* disclaimer */);

      var dto = noPackCheck().create(req);   // validatePack no-op; generate path uses the mock
      assertEquals("acme", dto.slug());
      assertEquals("packs/acme", dto.packRef());
      verify(packTemplate).generate(eq("acme"), eq("Acme"), anyString());
  }

  @Test
  void createWithoutPackRefPassesDisclaimerThrough() {
      when(brains.findBySlug("acme")).thenReturn(Optional.empty());
      when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
      when(packTemplate.generate(eq("acme"), eq("Acme"), eq("Custom note."))).thenReturn("packs/acme");
      var req = new BrainAdminController.CreateBrainRequest(
              "acme", "Acme", "", "local", null, null, null, "/c/acme",
              "anthropic", "m", "openai", "u", "Custom note.");
      noPackCheck().create(req);
      verify(packTemplate).generate("acme", "Acme", "Custom note.");
  }

  @Test
  void createWithExplicitPackRefDoesNotGenerate() {
      when(brains.findBySlug("testco")).thenReturn(Optional.empty());
      when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
      var req = new BrainAdminController.CreateBrainRequest(
              "testco", "Test Co", "src/test/resources/packs/test-pack", "local",
              null, null, null, "/c/testco", "anthropic", "m", "openai", "u", null);
      assertEquals("testco", controller.create(req).slug());   // real validatePack path
      verifyNoInteractions(packTemplate);
  }
  ```
  Keep `createRejectsPackSlugMismatch` (explicit mismatched packRef still 400s) — it already exercises the unchanged explicit branch; just add the trailing `null` disclaimer arg.

- [ ] **Make it pass — production edits in `BrainAdminController`:**
  1. Add field + ctor param:
     ```java
     private final PackTemplateService packTemplate;

     public BrainAdminController(BrainRepository brains, SyncService syncService,
                                 DomainPackRegistry packRegistry, ModelRouterService router,
                                 PackTemplateService packTemplate) {
         this.brains = brains;
         this.syncService = syncService;
         this.packRegistry = packRegistry;
         this.router = router;
         this.packTemplate = packTemplate;
     }
     ```
  2. Add `disclaimer` to `CreateBrainRequest` (trailing field, so JSON omission → null):
     ```java
     public record CreateBrainRequest(
             String slug, String displayName, String packRef, String sourceType,
             String s3Bucket, String s3Prefix, String s3Region, String localPath,
             String answerProvider, String answerModel,
             String utilityProvider, String utilityModel,
             String disclaimer) {}
     ```
  3. Branch `create` on a blank packRef (replace the `requireText("packRef", ...)` + `validatePack` section):
     ```java
     requireText("displayName", req.displayName());
     requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());

     String packRef;
     if (isBlank(req.packRef())) {
         // Frictionless path: auto-generate a slug-matched neutral starter pack.
         // generate() validates the result via DomainPackLoader, so no second validatePack.
         packRef = packTemplate.generate(slug, req.displayName().trim(), effectiveDisclaimer(req.disclaimer()));
     } else {
         packRef = req.packRef().trim();
         validatePack(packRef, slug);   // unchanged explicit-pack path (load + slug-match)
     }

     Brain brain = new Brain(UUID.randomUUID(), slug, req.displayName().trim());
     apply(brain, packRef, req.sourceType(), req.s3Bucket(), req.s3Prefix(), req.s3Region(),
             req.localPath(), req.answerProvider(), req.answerModel(),
             req.utilityProvider(), req.utilityModel());
     ```
     Add the helper:
     ```java
     private static final String DEFAULT_DISCLAIMER =
             "Educational use only — verify against the source documents.";
     private static String effectiveDisclaimer(String d) {
         return isBlank(d) ? DEFAULT_DISCLAIMER : d.trim();
     }
     ```
     (`apply(...)` already does `brain.setPackRef(trimToNull(packRef))` — pass the resolved `packRef`.) `update()` is unchanged.

- [ ] **Run green:** `./gradlew test --tests '*BrainAdminControllerTest'` then the full suite `./gradlew test`. Paste both passing summaries.
- [ ] **Commit:** `Phase 6b: auto-generate starter pack when create has no packRef`.

### Task 5 — gitignore runtime packs (backend, no test)

- [ ] Append to `.gitignore` (D5):
  ```gitignore
  # Runtime-generated brain packs (keep the committed template + msfg-mortgage)
  /packs/*
  !/packs/msfg-mortgage/
  !/packs/_template/
  ```
- [ ] **Verify:** `git status --porcelain packs/` shows `packs/_template/` is still trackable (it was added in Task 2 before this rule, so it's already tracked; the `!` re-include keeps it visible). Confirm a throwaway `packs/zzz-test/` dir is ignored: `mkdir -p packs/zzz-test && git status --porcelain packs/zzz-test` returns empty, then `rm -rf packs/zzz-test`.
- [ ] **Commit:** `Phase 6b: gitignore runtime-generated packs, keep committed ones`.

### Task 6 — Frontend: generate/existing toggle (build-green)

- [ ] **`types.ts`:** make `packRef` optional and add `disclaimer`:
  ```ts
  export interface BrainCreateRequest {
    slug: string;
    displayName: string;
    packRef?: string;          // omitted when generating a starter pack
    disclaimer?: string;       // only used when generating
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
- [ ] **`Brains.tsx`:** add a UI-only `generatePack` state (default `true`) — NOT part of `BrainCreateRequest`:
  ```tsx
  const [generatePack, setGeneratePack] = useState(true);
  ```
  Replace the `Pack ref` input block + hint (lines 90–94) with a mode toggle mirroring the existing `.mode-toggle` Source pattern:
  ```tsx
  <div className="setting-row">
    <label>Pack</label>
    <div className="mode-toggle">
      <button className={generatePack ? "on" : ""}
              onClick={() => setGeneratePack(true)}>Generate a starter pack (recommended)</button>
      <button className={!generatePack ? "on" : ""}
              onClick={() => setGeneratePack(false)}>Use an existing pack</button>
    </div>
  </div>
  {generatePack ? (
    <div className="setting-row">
      <label>Disclaimer (optional)</label>
      <input value={form.disclaimer ?? ""} onChange={(e) => set("disclaimer", e.target.value)}
             placeholder="Educational use only — verify against the source documents." />
    </div>
  ) : (
    <>
      <div className="setting-row">
        <label>Pack ref</label>
        <input value={form.packRef ?? ""} onChange={(e) => set("packRef", e.target.value)} />
      </div>
      <p className="muted">The pack's internal <code>slug</code> must equal this brain's slug. Copy <code>packs/msfg-mortgage</code> to a new folder, set its <code>slug</code>, and point here.</p>
    </>
  )}
  ```
  Update `blankForm` so it no longer hard-codes a packRef (generate is the default): set `packRef: ""` (or omit) and add `disclaimer: ""`.
- [ ] **Build the request in `create()`** so the generate mode sends NO `packRef`:
  ```tsx
  async function create() {
    setCreating(true); setError(null);
    try {
      const body: BrainCreateRequest = generatePack
        ? { ...form, packRef: undefined, disclaimer: form.disclaimer?.trim() || undefined }
        : { ...form, disclaimer: undefined };
      await brainsApi.create(body);
      setForm(blankForm); setGeneratePack(true);
      reload();
    } catch (e) { setError((e as Error).message); }
    finally { setCreating(false); }
  }
  ```
  (Sending `packRef: undefined` drops the key from `JSON.stringify` → Java DTO reads null → generate branch fires.) Adjust the submit `disabled` predicate: in existing-pack mode require `form.packRef?.trim()`; in generate mode no pack field is required.
- [ ] **`api.ts`:** `brainsApi.create` already takes `BrainCreateRequest` and POSTs it — no change needed beyond the type. Confirm `create`'s body type still compiles.
- [ ] **Build green:** `cd dashboard && npm run check && npm test`. Paste output.
- [ ] **Commit:** `Phase 6b: dashboard generate-vs-existing pack toggle`.

---

## Self-review checklist (run before declaring the phase done)

- [ ] **Part 1 before Part 2:** the loader-relaxation commit (Task 1) precedes the `_template`/`PackTemplateService`/controller commits. The neutral `retrieval.yaml` (`{}`/`[]`) would have failed boot before Task 1.
- [ ] **Existing packs still load byte-exact:** `MsfgGoldenPackTest` (14 acronyms, 4 programs) and `DomainPackLoaderTest.loadsAllFiveFilesIntoOnePack` (testco) green. No golden literal was edited.
- [ ] **No NPE on program-less pack:** `BrainPackBundle.of(emptyPack)` proven in Task 1 test; `RetrievalService.expandQuery`/`detectPrograms` verified to no-op on empty map/list (Context). No change to `RetrievalService` source was needed.
- [ ] **YAML-safe pack.yaml:** company name with `:`/quotes round-trips (Task 3 test). No naive `__PLACEHOLDER__` replace in generated `pack.yaml`.
- [ ] **Fail-fast cleanup:** a generated pack the loader rejects leaves no partial `packs/<slug>` dir (Task 3 test).
- [ ] **Explicit-pack path unchanged:** create-with-mismatched-packRef still 400s; `validatePack` still overridable for tests; `update()` untouched.
- [ ] **No secrets added:** `disclaimer` is the only new DTO field; no `local_api_key_ref` / `local_base_url` surface.
- [ ] **gitignore correct:** committed `packs/_template` + `packs/msfg-mortgage` tracked; a stray `packs/<slug>` ignored; `src/test/resources/packs/*` unaffected (leading-slash anchor).
- [ ] **All test-file positional ctors updated:** every `new CreateBrainRequest(...)` (+1 disclaimer arg) and every `new BrainAdminController(...)` (+1 PackTemplateService arg) compiles.
- [ ] **Frontend:** generate mode sends no `packRef`; existing mode unchanged; `npm run check`/`npm test` green.
- [ ] **Verification evidence pasted** for each backend task (superpowers:verification-before-completion) — no "should pass" claims.
- [ ] **Never touched `/Users/zacharyzink/MSFG/msfg-rag`.**

---

## Deferred (out of scope — note, do not build)

- **Configurable writable packs dir.** `PackTemplateService` writes to the working-dir `packs/`. A read-only bundled/containerized deploy (read-only classpath `packs/`) needs a configurable writable packs root (e.g. a `rag.packs-dir` property + `RagProperties` field, falling back to `./packs`). The service already takes the root via its seam ctor, so this is a one-property wiring change later.
- **Richer pack editing UI.** This phase generates a neutral pack but offers no dashboard editor for its prompt/guardrails/classifier/acronyms after creation. A future phase could expose a pack editor (the Rules editor already covers hard-rules/guidance; the rest stays file-edited).
- **Per-domain template variants.** A single neutral `_template`. Future: a small catalog (e.g. `legal`, `medical`, `support`) the user picks from at create time, each a compliance-reviewed `_template-<domain>/`, selected via a dropdown that maps to a `templateDir` in `PackTemplateService.generate`.
- **Regenerate/repair an existing pack.** No endpoint to re-clone the template over a drifted `packs/<slug>`; generation is create-time only.
