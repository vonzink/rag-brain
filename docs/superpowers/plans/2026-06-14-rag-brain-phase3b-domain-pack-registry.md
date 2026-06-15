# rag-brain Phase 3b (SP2) — DomainPackRegistry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Each task ends with the build + suite GREEN and a commit.

**Goal:** Replace the single immutable `DomainPack` `@Bean` (`config/DomainPackConfig.java:24-35`) with a per-brain `DomainPackRegistry` so each brain uses its OWN pack. The 7 pack-consumers become stateless with respect to the pack and resolve per-brain by an explicit `brainId` (mirroring Phase 3a's explicit-threading carrier — no ThreadLocal, no request-scoped bean). The two heavy precomputes (classifier regex compile, retrieval program compile) move into a per-brain cached `BrainPackBundle`. Single-brain behavior must stay byte-for-byte identical: the default brain's bundle reproduces today's pack, so the golden-pack and compliance suites pass unchanged.

**Architecture:** Phase 3 of the co-resident `brain_id` design (spec `docs/superpowers/specs/2026-06-14-rag-brain-multi-brain-design.md` §8, "Pack registry (the core refactor)"), split into sub-plans by a verification workflow. This is **SP2**. SP1 (Phase 3a) already threaded `brainId` as an explicit method parameter through `ask`/`retrieve`/`record` and stamped it on every write; this plan threads `brainId` the rest of the way into the pack-consuming services so each can resolve its OWN pack. The V7 column `DEFAULT` and the global `rule_revisions` table stay as-is (deferred — see "Deferred to later sub-plans"). **Out of scope:** brain-scoping the admin corpus counts (`AdminStatsController` `countByActiveTrue/count/chunks.count` — SP4), per-brain rule editing (`rule_revisions` has no `brain_id` — a later sub-plan), dropping the V7 DEFAULT (SP3/V8).

**Tech Stack:** Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 + pgvector · JUnit 5 + Mockito + Testcontainers. **All work in `/Users/zacharyzink/rag-brain`; never read or touch `/Users/zacharyzink/MSFG/msfg-rag`.**

---

## Context the engineer needs (verified, with exact locations)

### The atomicity rule (BLOCKER if violated)

The `DomainPack` `@Bean` in `DomainPackConfig.java:24-35` is injected into **8 sites**: 7 consumers (constructors) plus the loader's own construction. Removing that bean breaks Spring wiring for **every** consumer at once. Therefore: **introduce the registry first (the `DomainPack @Bean` stays, registry initially unused), migrate consumers one logical group at a time (each task green because the default brain's bundle reproduces today's pack), and remove the `DomainPack @Bean` LAST (final task), only after the last consumer no longer injects `DomainPack`.** Until the final task, both the bean and the registry coexist — that is intentional and required to keep each intermediate task green.

The 8 `DomainPack` references in `src/main` (verified by grep):

| File | How it uses `DomainPack` |
|---|---|
| `pack/DomainPack.java` | the record itself (unchanged) |
| `pack/DomainPackLoader.java` | constructs a `DomainPack` (unchanged — the registry uses the loader) |
| `config/DomainPackConfig.java` | the `@Bean` (removed in the FINAL task) |
| `service/ai/QuestionClassifierService.java` | ctor precompute → migrate (Task 3) |
| `service/retrieval/RetrievalService.java` | ctor fields `acronyms`/`programs` → migrate (Task 4) |
| `service/ai/PromptBuilderService.java` | ctor `template`/`disclaimer` → migrate (Task 5) |
| `service/ai/AnswerValidationService.java` | ctor `prohibitedPhrases`/`eligiblePhrase` → migrate (Task 5) |
| `service/ai/RulesService.java` | ctor field `pack` (fallback default) → migrate (Task 6) |
| `service/AskService.java` | ctor field `canned` → migrate (Task 7) |
| `controller/AdminStatsController.java` | ctor field `pack` → migrate (Task 7) |

(That is 7 consumers; `RulesService` and `AskService`/`AdminStatsController` are migrated in later tasks so the prompt path stays coherent.)

### Exact current signatures + state to relocate

**`DomainPack` record accessors** (`pack/DomainPack.java`): `slug()`, `companyName()`, `disclaimer()`, `promptTemplate()`, `hardRules()`, `guidance()`, `guardrails()` (→ `prohibitedPhrases()`, `eligiblePhrase()`, `cannedAnswers()` → `noSource()`/`escalation()`/`legal()`/`tax()`/`liveRates()`/`fraud()`), `classifierRules()` (`List<ClassifierRule>` with `category()`, `patterns()`), `acronymExpansions()` (`Map<String,String>`), `programRules()` (`List<ProgramRule>` with `program()`, `keywords()`, `wordPatterns()`).

**`QuestionClassifierService`** (`service/ai/QuestionClassifierService.java:25-52`): field `private final Map<QuestionCategory, List<Pattern>> rules;` built in the ctor (lines 29-36) from `pack.classifierRules()` via `rule.patterns().stream().map(Pattern::compile).toList()` into a `LinkedHashMap` (insertion order = check order). `classify(String question)` (line 38) iterates `rules`. **This compiled map is the per-brain "classifier regex" precompute that moves into the bundle.**

**`RetrievalService`** (`service/retrieval/RetrievalService.java`): ctor (49-64) sets `this.acronyms = pack.acronymExpansions();` (line 62) and `this.programs = compilePrograms(pack.programRules());` (line 63). `record CompiledProgram(String name, List<String> keywords, List<java.util.regex.Pattern> patterns)` is nested at **line 67**. `static List<CompiledProgram> compilePrograms(List<DomainPack.ProgramRule> rules)` at 69-75. `retrieve(String question, UUID brainId)` (line 78 — already takes `brainId` from SP1) uses `acronyms` in `expandQuery(question, acronyms)` (line 96) and `programs` in `detectPrograms(question, programs)` (line 121) and `detectPrograms(... , programs)` (line 156, inside `toRetrievedChunk`). `expandQuery`, `toOrQuery`, `detectPrograms`, `programScoreFactor` are all `static` package-private helpers. **`acronyms` + `programs` are the per-brain "retrieval program" precompute that moves into the bundle; `CompiledProgram` moves to `com.msfg.rag.pack` (top-level).**

**`PromptBuilderService`** (`service/ai/PromptBuilderService.java:17-41`): ctor (23-27) sets `this.template = pack.promptTemplate();`, `this.disclaimer = pack.disclaimer();`, `this.rules = rules;`. `disclaimer()` (30) returns the field. `build(String question, List<RetrievedChunk> chunks)` (34-41) calls `template.formatted(rules.effectiveHard(), rules.effectiveGuidance(), formatContext(chunks), question, disclaimer)`.

**`AnswerValidationService`** (`service/ai/AnswerValidationService.java:18-52`): ctor (23-26) sets `this.prohibitedPhrases = pack.guardrails().prohibitedPhrases();`, `this.eligiblePhrase = pack.guardrails().eligiblePhrase();`. `validate(ModelAnswer answer, boolean evidenceWasSufficient)` (28) reads both fields.

**`RulesService`** (`service/ai/RulesService.java:22-149`): ctor `RulesService(RuleRevisionRepository repo, DomainPack pack)` (48-51), field `private final DomainPack pack;` (41). `effectiveHard()` (55) → `effective("rules.hard", pack.hardRules())`; `effectiveGuidance()` (59) → `effective("rules.guidance", pack.guidance())`; `state()` (64-69) uses `pack.hardRules()`/`pack.guidance()` as the pack default. The DB cache (`snapshot()` 133-142) is keyed by GLOBAL keys `"rules.hard"`/`"rules.guidance"` and `rule_revisions` has **no `brain_id`**. **DEFER per-brain rule editing** (a later sub-plan adds `brain_id` to `rule_revisions`); in this plan only the **pack-default fallback** becomes per-brain. `KEYS`, `save`, `revert`, `history`, `invalidate` stay global.

**`AskService`** (`service/AskService.java:43-308`): ctor first param `DomainPack pack` (60); field `private final DomainPack.CannedAnswers canned = pack.guardrails().cannedAnswers();` (47, set at 71). `ask(AskRequest request, UUID brainId)` (85 — already takes `brainId` from SP1) calls `questionClassifierService.classify(request.question())` (91), `retrievalService.retrieve(request.question(), brainId)` (98), `promptBuilderService.build(request.question(), retrieval.chunks())` (107), `answerValidationService.validate(modelAnswer, true)` (142), `promptBuilderService.disclaimer()` (170, 202), and `categoryAnswer(category, canned)` (94 → helper 175-185 reads `canned`).

**`AdminStatsController`** (`controller/AdminStatsController.java:13-37`): ctor `AdminStatsController(DomainPack pack, MortgageDocumentRepository documents, DocumentChunkRepository chunks)` (23); field `private final DomainPack pack;` (19). `stats()` (32-36) builds `new BrainDto(pack.companyName(), pack.slug())` and the corpus counts. **Corpus counts stay global (deferred to SP4); only the BrainDto becomes per-brain.**

**`DomainPackConfig`** (`config/DomainPackConfig.java:24-35`): `domainPack(@Value("${brain.pack:packs/msfg-mortgage}") String packDir, @Value("${brain.slug:mortgage}") String slug)` loads `new DomainPackLoader().load(Path.of(packDir).toAbsolutePath().normalize())` then validates `pack.slug().equals(slug)`, throwing `IllegalStateException` on mismatch. **The registry reproduces this load+validate per brain; the `@Bean` is removed in the FINAL task. `DomainPackLoader` stays.**

### Resolution + lazy-load primitives (verified)

- `BrainRepository.findById(UUID)` (from `JpaRepository`), `findDefaultBrain()` (`@Query`), `findBySlug(String)` all exist (`repository/BrainRepository.java`).
- `Brain.getId()` / `getSlug()` / `getPackRef()` / `isActive()` exist (`domain/Brain.java`). `getPackRef()` returns a repo-relative path like `packs/msfg-mortgage` (set by `DefaultBrainSeeder.run` from `${brain.pack}` — `config/DefaultBrainSeeder.java:38,49,66`).
- `DefaultBrainSeeder` (an `ApplicationRunner`, `config/DefaultBrainSeeder.java`) reconciles the default brain's `packRef`/`slug` from env at boot, BEFORE any request. The default brain ROW already exists from V7 (`00000000-0000-0000-0000-000000000001`). **This is why the registry can lazy-load:** `bundle(brainId)` runs only on a request, by which time the seeder has set `packRef` — so the registry never needs the bean at construction and sidesteps boot ordering.
- Test constant `TestBrains.DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")` (`src/test/java/com/msfg/rag/TestBrains.java`).

### Test landscape (verified — how services are constructed today)

NO test uses `@SpringBootTest` (grep returns nothing), and NO test uses `@MockBean` (grep returns nothing). Every consumer test constructs its service **directly** with `TestPacks.msfg()` (`src/test/java/com/msfg/rag/pack/TestPacks.java` — lazy memoized `new DomainPackLoader().load(Path.of("packs/msfg-mortgage"))`). So **no integration test depends on the `DomainPack` bean wiring, and none needs a seeded DB brain row.** This means the cleanest test fixture for the registry is a **preloaded registry** (a test-only seam that maps a brain id directly to a pre-built `BrainPackBundle`), with no `BrainRepository` mock and no Testcontainers needed for the consumer unit tests. The construction sites to update:

| Test file | Current construction | New construction |
|---|---|---|
| `pack/TestPacks.java` | provides `DomainPack msfg()` | ADD `DomainPackRegistry registryFor(UUID, DomainPack)` + `registry()` (default-id → msfg) helpers |
| `service/ai/QuestionClassifierServiceTest.java:15` | `new QuestionClassifierService(TestPacks.msfg())`, `classifier.classify(q)` | `new QuestionClassifierService(TestPacks.registry())`, `classify(q, DEFAULT_ID)` |
| `service/retrieval/RetrievalServiceTest.java:16-17` | `RetrievalService.CompiledProgram`, `RetrievalService.compilePrograms(...)` | `com.msfg.rag.pack.CompiledProgram`, `com.msfg.rag.pack.CompiledProgram.compile(...)` (helpers stay `static` on `RetrievalService`) |
| `service/ai/PromptBuilderServiceTest.java:27` | `new PromptBuilderService(TestPacks.msfg(), rulesService)`, `build(q, chunks)`, `rules.effectiveHard()` stub | `new PromptBuilderService(TestPacks.registry(), rulesService)`, `build(q, chunks, DEFAULT_ID)`, `rules.effectiveHard(DEFAULT_ID)` stub |
| `service/ai/AnswerValidationServiceTest.java:22` | `new AnswerValidationService(TestPacks.msfg())`, `validate(a, bool)` | `new AnswerValidationService(TestPacks.registry())`, `validate(a, bool, DEFAULT_ID)` |
| `service/ai/RulesServiceTest.java:31` | `new RulesService(repo, TestPacks.msfg())`, `effectiveHard()`/`effectiveGuidance()`/`state()` | `new RulesService(repo, TestPacks.registry())`, `effectiveHard(DEFAULT_ID)`/`effectiveGuidance(DEFAULT_ID)`/`state(DEFAULT_ID)` |
| `service/AskServiceTest.java:95,123` | `new AskService(TestPacks.msfg(), ...)`, mocks `classifier.classify(anyString())`, `promptBuilder.build(anyString(), anyList())`, `promptBuilder.disclaimer()`, `retrieval.retrieve(anyString(), any())` | `new AskService(TestPacks.registry(), ...)`, `classify(anyString(), any())`, `build(anyString(), anyList(), any())`, `disclaimer(any())`, `validate(any(), anyBoolean(), any())` |
| `service/AskServiceBrainTest.java:72` | `new AskService(TestPacks.msfg(), ...)`, same mock surface | `new AskService(TestPacks.registry(), ...)` + the param-bearing mock stubs |
| `controller/AdminStatsControllerTest.java:23` | `new AdminStatsController(TestPacks.msfg(), docs, chunks)`, `controller.stats()` | `new AdminStatsController(TestPacks.registry(), brainResolver, docs, chunks)`, `stats(null)` (a mock `BrainResolver` returning a default `Brain` with id `DEFAULT_ID`) |
| `pack/MsfgGoldenPackTest.java:218` | `new PromptBuilderService(PACK, rulesService).build("Q", List.of())` | `new PromptBuilderService(TestPacks.registry(), rulesService).build("Q", List.of(), DEFAULT_ID)` + stub `effectiveHard(DEFAULT_ID)`/`effectiveGuidance(DEFAULT_ID)` |

`MsfgGoldenPackTest` must keep passing UNCHANGED in intent: all its pack-content assertions (`PACK.slug()` etc.) are untouched; only the ONE assembly test (`defaultAssemblyIsByteExact`, line 213-232) constructs a `PromptBuilderService` and must adopt the new registry + `build(..., DEFAULT_ID)` signature. The golden literals stay identical.

`RetrievalServiceTest` exercises only the `static` helpers (`toOrQuery`, `expandQuery`, `detectPrograms`, `programScoreFactor`) — it never constructs a `RetrievalService`. So it needs only the `CompiledProgram` relocation, no registry fixture.

---

## File structure (new + modified)

### New files (5)

```
src/main/java/com/msfg/rag/pack/CompiledProgram.java        # moved from RetrievalService nested record (top-level)
src/main/java/com/msfg/rag/pack/BrainPackBundle.java        # per-brain pack + derived state holder
src/main/java/com/msfg/rag/pack/DomainPackRegistry.java     # lazy thread-safe per-brain cache
src/test/java/com/msfg/rag/pack/DomainPackRegistryTest.java # registry load/validate/cache/reload tests (Testcontainers-free; mocked BrainRepository)
src/test/java/com/msfg/rag/pack/BrainPackBundleTest.java    # bundle precompute correctness (classifier map + programs + acronyms)
```

### Modified files (15)

```
src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java       # remove nested CompiledProgram + acronyms/programs fields; inject registry
src/main/java/com/msfg/rag/service/ai/QuestionClassifierService.java     # remove ctor precompute; inject registry; classify(q, brainId)
src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java          # inject registry; build(q, chunks, brainId); disclaimer(brainId)
src/main/java/com/msfg/rag/service/ai/AnswerValidationService.java       # inject registry; validate(a, bool, brainId)
src/main/java/com/msfg/rag/service/ai/RulesService.java                  # inject registry; effectiveHard(brainId)/effectiveGuidance(brainId)/state(brainId)
src/main/java/com/msfg/rag/service/AskService.java                       # inject registry; thread brainId into classify/build/validate/disclaimer/canned
src/main/java/com/msfg/rag/controller/AdminStatsController.java          # inject registry + BrainResolver; per-brain BrainDto
src/main/java/com/msfg/rag/controller/AdminRulesController.java          # inject BrainResolver; thread brainId into build()/state()
src/main/java/com/msfg/rag/config/DomainPackConfig.java                  # DELETED — the DomainPack @Bean is removed (FINAL task)
src/test/java/com/msfg/rag/pack/TestPacks.java                          # add registry fixtures
src/test/java/com/msfg/rag/service/ai/QuestionClassifierServiceTest.java
src/test/java/com/msfg/rag/service/retrieval/RetrievalServiceTest.java
src/test/java/com/msfg/rag/service/ai/PromptBuilderServiceTest.java
src/test/java/com/msfg/rag/service/ai/AnswerValidationServiceTest.java
src/test/java/com/msfg/rag/service/ai/RulesServiceTest.java
src/test/java/com/msfg/rag/service/AskServiceTest.java
src/test/java/com/msfg/rag/service/AskServiceBrainTest.java
src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java
src/test/java/com/msfg/rag/controller/AdminRulesControllerTest.java
src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java
```

**Counts:** new = 5 files. Modified/deleted production = 10 (`QuestionClassifierService`, `RetrievalService`, `PromptBuilderService`, `AnswerValidationService`, `RulesService`, `AskService`, `AdminStatsController`, `AdminRulesController` modified; `DomainPackConfig` deleted; `DomainPackLoader` unchanged). Modified tests = 11. **Total files touched: 26** (5 new + 21 modified/deleted).

---

### Task 0: Green baseline

- [ ] **Step 1:** `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL` (Docker required for Testcontainers). A red baseline → stop and report; do not start on top of red.

---

### Task 1: Relocate `CompiledProgram` to `com.msfg.rag.pack` (top-level)

Move the nested `CompiledProgram` record out of `RetrievalService` so the registry/bundle and `RetrievalService` can share it without a circular dependency. No behavior change; `RetrievalService` keeps the same fields and `compilePrograms` for now (registry not built yet).

**Files:** create `pack/CompiledProgram.java`; modify `service/retrieval/RetrievalService.java`; modify `service/retrieval/RetrievalServiceTest.java`.

- [ ] **Step 1: Create `src/main/java/com/msfg/rag/pack/CompiledProgram.java`**

```java
package com.msfg.rag.pack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A program-detection rule with its word-boundary regexes precompiled.
 * Lives in the pack package (not nested in RetrievalService) so the
 * DomainPackRegistry can build and cache it per brain and RetrievalService
 * can consume it — without a circular dependency between the two.
 */
public record CompiledProgram(String name, List<String> keywords, List<Pattern> patterns) {

    /** Compiles a pack's {@link DomainPack.ProgramRule} list into detection-ready programs. */
    public static List<CompiledProgram> compile(List<DomainPack.ProgramRule> rules) {
        return rules.stream()
                .map(r -> new CompiledProgram(
                        r.program(),
                        r.keywords(),
                        r.wordPatterns().stream().map(Pattern::compile).toList()))
                .toList();
    }
}
```

- [ ] **Step 2: Update `RetrievalService` to use the relocated record**

In `service/retrieval/RetrievalService.java`:
- Add import: `import com.msfg.rag.pack.CompiledProgram;` (keep `import com.msfg.rag.pack.DomainPack;`).
- DELETE the nested record at line 67: `record CompiledProgram(String name, List<String> keywords, List<java.util.regex.Pattern> patterns) {}`.
- DELETE the `static List<CompiledProgram> compilePrograms(...)` method (lines 69-75) — it is replaced by `CompiledProgram.compile(...)`.
- In the ctor (line 63), change `this.programs = compilePrograms(pack.programRules());` to `this.programs = CompiledProgram.compile(pack.programRules());`.
- The field type `private final List<CompiledProgram> programs;` (line 47) now refers to `com.msfg.rag.pack.CompiledProgram` (via the new import) — no edit needed to the declaration text.
- `detectPrograms(String text, List<CompiledProgram> programs)` (line 250) and its callers (121, 156) are unchanged — same simple name, now resolved to the pack type.

- [ ] **Step 3: Update `RetrievalServiceTest`**

In `service/retrieval/RetrievalServiceTest.java`, lines 16-17, change:
```java
    private static final java.util.List<RetrievalService.CompiledProgram> PROGRAMS =
            RetrievalService.compilePrograms(TestPacks.msfg().programRules());
```
to:
```java
    private static final java.util.List<com.msfg.rag.pack.CompiledProgram> PROGRAMS =
            com.msfg.rag.pack.CompiledProgram.compile(TestPacks.msfg().programRules());
```
(The `detectPrograms`/`programScoreFactor`/`toOrQuery`/`expandQuery` calls in this test are unchanged — those helpers stay `static` on `RetrievalService` and accept `List<CompiledProgram>`.)

- [ ] **Step 4: Run → PASS + commit**

```bash
./gradlew test
```
→ green (pure relocation, no behavior change). Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: relocate CompiledProgram to com.msfg.rag.pack

Move the nested CompiledProgram record + its compile() out of RetrievalService
into a top-level pack record so the upcoming DomainPackRegistry and
RetrievalService can share it without a circular dependency. No behavior change.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Introduce `BrainPackBundle` + `DomainPackRegistry` (unused by consumers)

Add the two new types. The `DomainPack @Bean` stays present and every consumer still injects it — the registry is wired but not yet consumed, so the whole suite stays green. The registry loads + validates a brain's pack lazily (per `brainId`) and caches the derived state.

**Files:** create `pack/BrainPackBundle.java`, `pack/DomainPackRegistry.java`, `pack/BrainPackBundleTest.java`, `pack/DomainPackRegistryTest.java`; modify `pack/TestPacks.java`.

- [ ] **Step 1: Create `src/main/java/com/msfg/rag/pack/BrainPackBundle.java`**

```java
package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One brain's pack plus the derived state that used to be precomputed in
 * service constructors: the compiled classifier regex map (insertion order =
 * check order) and the compiled retrieval programs. Built once per brain by
 * {@link DomainPackRegistry} and cached, so the two heavy precomputes happen
 * per brain rather than once at application boot.
 */
public record BrainPackBundle(
        DomainPack pack,
        Map<QuestionCategory, List<Pattern>> classifierPatterns,
        List<CompiledProgram> programs,
        Map<String, String> acronyms) {

    /** Builds the per-brain derived state from a loaded, already-validated pack. */
    public static BrainPackBundle of(DomainPack pack) {
        Map<QuestionCategory, List<Pattern>> compiled = new LinkedHashMap<>();
        for (DomainPack.ClassifierRule rule : pack.classifierRules()) {
            compiled.put(rule.category(),
                    rule.patterns().stream().map(Pattern::compile).toList());
        }
        return new BrainPackBundle(
                pack,
                Map.copyOf(compiled),
                CompiledProgram.compile(pack.programRules()),
                pack.acronymExpansions());
    }
}
```

> **Note on `Map.copyOf` + ordering:** `classifierPatterns` is read by `QuestionClassifierService.classify` which iterates entries as *check order*. `Map.copyOf` does NOT preserve insertion order. To preserve check order, `classify` must iterate the pack's `classifierRules()` order, OR the bundle must expose an order-stable structure. **Decision (resolved):** keep an order-stable `LinkedHashMap` — replace `Map.copyOf(compiled)` with `java.util.Collections.unmodifiableMap(compiled)` (the `compiled` `LinkedHashMap` is freshly built and never leaked, so wrapping it unmodifiable is safe and preserves insertion order). Use this in the code above instead of `Map.copyOf(compiled)`:
> ```java
>                 java.util.Collections.unmodifiableMap(compiled),
> ```

Apply that one substitution (`Collections.unmodifiableMap(compiled)` not `Map.copyOf(compiled)`) when writing the file.

- [ ] **Step 2: Create `src/main/java/com/msfg/rag/pack/DomainPackRegistry.java`**

```java
package com.msfg.rag.pack;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-brain pack registry: replaces the single immutable DomainPack bean so
 * each brain runs its OWN pack. Lazy + thread-safe — a brain's pack is loaded,
 * validated, and its derived state precompiled on first use and cached.
 *
 * Lazy-loading sidesteps boot ordering: the default brain row exists from V7
 * and DefaultBrainSeeder reconciles its packRef before any request, so the
 * registry never needs the pack at construction. New/edited brains load +
 * validate their pack here (fail-fast), preserving the per-deployment guard
 * that a brain's slug must match its pack's slug.
 */
@Component
public class DomainPackRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainPackRegistry.class);

    private final BrainRepository brains;
    private final DomainPackLoader loader;
    private final ConcurrentHashMap<UUID, BrainPackBundle> cache = new ConcurrentHashMap<>();

    public DomainPackRegistry(BrainRepository brains) {
        this(brains, new DomainPackLoader());
    }

    /** Test seam: inject a loader (and preload bundles via {@link #preload}). */
    DomainPackRegistry(BrainRepository brains, DomainPackLoader loader) {
        this.brains = brains;
        this.loader = loader;
    }

    /** The brain's bundle, loaded + validated + cached on first use. */
    public BrainPackBundle bundle(UUID brainId) {
        return cache.computeIfAbsent(brainId, this::load);
    }

    /** Evicts a brain's cached bundle so the next call reloads it (Phase 6 brain edits). */
    public void reload(UUID brainId) {
        cache.remove(brainId);
    }

    private BrainPackBundle load(UUID brainId) {
        Brain brain = brains.findById(brainId)
                .orElseThrow(() -> new IllegalStateException("No brain for id " + brainId));
        Path packDir = Path.of(brain.getPackRef()).toAbsolutePath().normalize();
        DomainPack pack = loader.load(packDir);
        if (!pack.slug().equals(brain.getSlug())) {
            throw new IllegalStateException("brain '" + brain.getSlug() + "' (" + brainId
                    + ") declares pack '" + brain.getPackRef() + "' with slug '" + pack.slug()
                    + "' — brain/pack slug mismatch");
        }
        log.info("Domain pack loaded for brain '{}' ({}): company='{}', path={}",
                brain.getSlug(), brainId, pack.companyName(), packDir);
        return BrainPackBundle.of(pack);
    }

    /** Test-only: seed a pre-built bundle for a brain id without touching the DB or filesystem. */
    void preload(UUID brainId, BrainPackBundle bundle) {
        cache.put(brainId, bundle);
    }
}
```

> **Why a package-private `preload` + package-private loader ctor:** the consumer unit tests live in matching packages or use the `TestPacks` factory (in `com.msfg.rag.pack`), so package-private visibility is enough for `TestPacks` to build a preloaded registry. No public test-only API leaks onto the production surface. `DomainPackRegistryTest` (same package) uses the loader-injecting ctor + a mocked `BrainRepository` to exercise the real load/validate/cache/reload path.

- [ ] **Step 3: Add registry fixtures to `src/test/java/com/msfg/rag/pack/TestPacks.java`**

Append two helpers (keeps the existing `msfg()`):
```java
    /** A registry preloaded with one brain id → bundle, for consumer unit tests. */
    public static DomainPackRegistry registryFor(java.util.UUID brainId, DomainPack pack) {
        DomainPackRegistry registry =
                new DomainPackRegistry(org.mockito.Mockito.mock(
                        com.msfg.rag.repository.BrainRepository.class));
        registry.preload(brainId, BrainPackBundle.of(pack));
        return registry;
    }

    /** The default-brain registry (DEFAULT_ID → the real MSFG pack). */
    public static DomainPackRegistry registry() {
        return registryFor(com.msfg.rag.TestBrains.DEFAULT_ID, msfg());
    }
```
(`registryFor` mocks `BrainRepository` only to satisfy the public ctor; `preload` bypasses it so no stubbing is needed. `BrainPackBundle.of` + `preload` are package-private and `TestPacks` is in `com.msfg.rag.pack`, so this compiles.)

- [ ] **Step 4: Create `src/test/java/com/msfg/rag/pack/BrainPackBundleTest.java`**

```java
package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundle precomputes the same derived state the service constructors used to build. */
class BrainPackBundleTest {

    private final BrainPackBundle bundle = BrainPackBundle.of(TestPacks.msfg());

    @Test
    void classifierPatternsPreserveCheckOrder() {
        // Insertion order from classifier.yaml: FRAUD first (priority), then ELIGIBILITY...
        List<QuestionCategory> order = List.copyOf(bundle.classifierPatterns().keySet());
        assertEquals(QuestionCategory.FRAUD, order.get(0));
        assertEquals(QuestionCategory.ELIGIBILITY, order.get(1));
        assertEquals(5, order.size());
    }

    @Test
    void classifierPatternsCompileEveryRule() {
        for (var entry : bundle.classifierPatterns().entrySet()) {
            assertTrue(entry.getValue().stream().allMatch(p -> p instanceof Pattern),
                    "every classifier pattern must be a compiled Pattern for " + entry.getKey());
            assertTrue(!entry.getValue().isEmpty(), "no empty pattern list for " + entry.getKey());
        }
    }

    @Test
    void programsAndAcronymsComeFromThePack() {
        assertEquals(TestPacks.msfg().acronymExpansions(), bundle.acronyms());
        assertEquals(List.of("FHA", "VA", "USDA", "CONVENTIONAL"),
                bundle.programs().stream().map(CompiledProgram::name).toList());
    }

    @Test
    void packAccessorReturnsTheSamePack() {
        assertEquals(TestPacks.msfg(), bundle.pack());
    }
}
```

- [ ] **Step 5: Create `src/test/java/com/msfg/rag/pack/DomainPackRegistryTest.java`**

```java
package com.msfg.rag.pack;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Load + validate + cache + reload, exercising the real loader against the MSFG pack. */
class DomainPackRegistryTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Brain brain(String slug, String packRef) {
        // packRef points at the real pack dir; slug must match the pack's slug.
        Brain b = new Brain(ID, slug, slug);
        b.setPackRef(packRef);
        return b;
    }

    @Test
    void loadsValidatesAndCaches() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.of(brain("mortgage", "packs/msfg-mortgage")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        BrainPackBundle first = registry.bundle(ID);
        BrainPackBundle second = registry.bundle(ID);

        assertEquals("mortgage", first.pack().slug());
        assertSame(first, second, "bundle must be cached, not reloaded");
        verify(brains, times(1)).findById(ID); // computeIfAbsent loads exactly once
    }

    @Test
    void rejectsBrainPackSlugMismatch() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        // brain slug "hr" but the pack at packs/msfg-mortgage declares "mortgage"
        when(brains.findById(ID)).thenReturn(Optional.of(brain("hr", "packs/msfg-mortgage")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.bundle(ID));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("slug mismatch"));
    }

    @Test
    void rejectsUnknownBrain() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.empty());
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        assertThrows(IllegalStateException.class, () -> registry.bundle(ID));
    }

    @Test
    void reloadEvictsSoNextCallRebuilds() {
        BrainRepository brains = Mockito.mock(BrainRepository.class);
        when(brains.findById(ID)).thenReturn(Optional.of(brain("mortgage", "packs/msfg-mortgage")));
        DomainPackRegistry registry = new DomainPackRegistry(brains, new DomainPackLoader());

        BrainPackBundle first = registry.bundle(ID);
        registry.reload(ID);
        BrainPackBundle rebuilt = registry.bundle(ID);

        org.junit.jupiter.api.Assertions.assertNotSame(first, rebuilt, "reload must rebuild the bundle");
        verify(brains, times(2)).findById(ID);
    }
}
```

- [ ] **Step 6: Run → PASS + commit**

`./gradlew test` → green (new types compile and pass; no consumer changed yet, so existing tests still construct services with `DomainPack` and pass). Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: add BrainPackBundle + DomainPackRegistry (not yet consumed)

Lazy, thread-safe per-brain cache: bundle(brainId) loads + validates the
brain's pack (slug guard preserved) and precompiles its classifier regex map +
retrieval programs. reload(brainId) evicts for later brain edits. The single
DomainPack @Bean and all consumers are untouched — both coexist until each
consumer migrates.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Migrate `QuestionClassifierService` to the registry

Remove the ctor precompute + `rules` field; inject the registry; `classify(String question, UUID brainId)` resolves `registry.bundle(brainId).classifierPatterns()` per call.

**Files:** modify `service/ai/QuestionClassifierService.java`, `service/AskService.java` (call site only — temporary `DEFAULT`-free passthrough), `service/ai/QuestionClassifierServiceTest.java`, `service/AskServiceTest.java`, `service/AskServiceBrainTest.java`.

- [ ] **Step 1: Update `QuestionClassifierServiceTest` (failing first)**

In `service/ai/QuestionClassifierServiceTest.java`:
- Line 15: `private final QuestionClassifierService classifier = new QuestionClassifierService(TestPacks.registry());`
- Add import: `import static com.msfg.rag.TestBrains.DEFAULT_ID;`
- Every `classifier.classify(question)` call (lines 35, 49, 61, 72, 84, 99, 107) → `classifier.classify(question, DEFAULT_ID)`.

Run `./gradlew test --tests "com.msfg.rag.service.ai.QuestionClassifierServiceTest"` → compile FAIL (no registry ctor / no 2-arg classify yet).

- [ ] **Step 2: Implement `QuestionClassifierService`**

Replace the class body of `service/ai/QuestionClassifierService.java` with:
```java
package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPackRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rule-based pre-classifier that runs BEFORE retrieval. Catches questions the
 * bot must never answer (rag.md guardrails) without spending an embedding or
 * LLM call on them.
 *
 * Rule order comes from the brain's pack classifier.yaml list; FRAUD first by
 * pack convention so "can I hide debt to qualify?" is refused as fraud, not
 * escalated as an eligibility question. The compiled patterns are resolved
 * per brain from the DomainPackRegistry (bundle cache), not precomputed once.
 *
 * COMPLIANCE-CRITICAL: patterns are defined in the domain pack.
 * Additions are fine; removals or loosening need review.
 */
@Service
public class QuestionClassifierService {

    private final DomainPackRegistry registry;

    public QuestionClassifierService(DomainPackRegistry registry) {
        this.registry = registry;
    }

    public QuestionCategory classify(String question, UUID brainId) {
        if (question == null || question.isBlank()) {
            return QuestionCategory.EDUCATIONAL;
        }
        String normalized = question.toLowerCase(Locale.US).strip();

        Map<QuestionCategory, List<Pattern>> rules = registry.bundle(brainId).classifierPatterns();
        for (Map.Entry<QuestionCategory, List<Pattern>> entry : rules.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(normalized).find()) {
                    return entry.getKey();
                }
            }
        }
        return QuestionCategory.EDUCATIONAL;
    }
}
```

- [ ] **Step 3: Thread `brainId` into the `AskService` call site**

In `service/AskService.java:91`, change `questionClassifierService.classify(request.question())` to `questionClassifierService.classify(request.question(), brainId)`. (`ask` already has `brainId` from SP1; no signature change.)

- [ ] **Step 4: Fix `AskServiceTest` + `AskServiceBrainTest` mock surface for `classify`**

In `service/AskServiceTest.java`:
- Line 71: `when(classifier.classify(anyString())).thenReturn(QuestionCategory.EDUCATIONAL);` → `when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);`
- Line 103 (in `askServiceClassifying`): `when(classifier.classify(anyString())).thenReturn(category);` → `when(classifier.classify(anyString(), any())).thenReturn(category);`
- `any` is already statically imported (line 36).

In `service/AskServiceBrainTest.java:58`: `when(classifier.classify(anyString())).thenReturn(QuestionCategory.EDUCATIONAL);` → `when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);` (`any` already imported line 28).

(These two test files still construct `AskService` with `TestPacks.msfg()` — that stays valid until Task 7 migrates `AskService`. Only the classifier mock signature changes here.)

- [ ] **Step 5: Run → PASS + commit**

`./gradlew test` → green. Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: QuestionClassifierService resolves classifier regex per brain

Inject DomainPackRegistry; classify(question, brainId) reads the brain's
compiled classifier-pattern map from the bundle cache instead of a ctor-built
field. AskService passes the resolved brainId.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Migrate `RetrievalService` to the registry

Remove the `acronyms`/`programs` fields; inject the registry; resolve `acronyms` + `programs` per brain inside `retrieve`. The `static` helpers (`expandQuery`, `detectPrograms`, etc.) keep taking the data as parameters — only the SOURCE of that data changes.

**Files:** modify `service/retrieval/RetrievalService.java`. (No new test fixture needed — `RetrievalServiceTest` exercises only static helpers and already uses `CompiledProgram.compile` from Task 1. `AskServiceTest`/`AskServiceBrainTest` mock `RetrievalService`, so no change there.)

- [ ] **Step 1: Edit `RetrievalService`**

In `service/retrieval/RetrievalService.java`:
- Add import `import com.msfg.rag.pack.DomainPackRegistry;` (keep `import com.msfg.rag.pack.CompiledProgram;` from Task 1; you may now DROP `import com.msfg.rag.pack.DomainPack;` if no other use remains — `compilePrograms` is gone, so confirm and remove it).
- DELETE the two fields (lines 46-47): `private final Map<String, String> acronyms;` and `private final List<CompiledProgram> programs;`.
- In the ctor (49-64): replace the `DomainPack pack` parameter with `DomainPackRegistry packRegistry`; store `private final DomainPackRegistry packRegistry;` (declare the field where `acronyms`/`programs` were). DELETE the two assignments `this.acronyms = pack.acronymExpansions();` (62) and `this.programs = compilePrograms(pack.programRules());` (63); add `this.packRegistry = packRegistry;`.
- In `retrieve(String question, UUID brainId)` (78): after the null/blank guard, resolve the bundle once:
  ```java
  BrainPackBundle bundle = packRegistry.bundle(brainId);
  Map<String, String> acronyms = bundle.acronyms();
  List<CompiledProgram> programs = bundle.programs();
  ```
  (Add `import com.msfg.rag.pack.BrainPackBundle;`.) Place this right after the `if (question == null || question.isBlank()) return RetrievalResult.empty();` block (so empty questions don't touch the registry).
- Line 96 `expandQuery(question, acronyms)` now reads the local `acronyms` — unchanged text, resolves to the local.
- Line 121 `detectPrograms(question, programs)` now reads the local `programs` — unchanged text.
- Line 156-158 `detectPrograms(..., programs)` inside `toRetrievedChunk` (a separate private method) needs the brain's `programs`. **`toRetrievedChunk` is called from the `retrieve` stream (line 123).** Change `toRetrievedChunk(MutableHit hit, java.util.Set<String> questionPrograms)` (146) to `toRetrievedChunk(MutableHit hit, java.util.Set<String> questionPrograms, List<CompiledProgram> programs)` and pass `programs` from the stream at line 123: `.map(hit -> toRetrievedChunk(hit, questionPrograms, programs))`. Inside `toRetrievedChunk`, line 156-158 `detectPrograms(..., programs)` now reads the passed parameter.

- [ ] **Step 2: Run → PASS + commit**

`./gradlew test` → green (the static-helper tests are unaffected; `RetrievalService` now sources acronyms/programs per brain). Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: RetrievalService resolves acronyms + programs per brain

Inject DomainPackRegistry; retrieve(question, brainId) reads the brain's
acronym map and compiled programs from the bundle cache instead of ctor
fields. Static helpers still take the data as params; only the source changed.
toRetrievedChunk takes the brain's programs explicitly.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Migrate `PromptBuilderService` + `AnswerValidationService` to the registry

Both are pack-data readers consumed by `AskService`. Migrate them together so the prompt/validate path stays coherent; thread `brainId` into both signatures.

**Files:** modify `service/ai/PromptBuilderService.java`, `service/ai/AnswerValidationService.java`, `controller/AdminRulesController.java` (the `preview()` `build` call site); modify tests `service/ai/PromptBuilderServiceTest.java`, `service/ai/AnswerValidationServiceTest.java`, `pack/MsfgGoldenPackTest.java`, `controller/AdminRulesControllerTest.java`. (Also touches `AskService` call sites + `AskServiceTest`/`AskServiceBrainTest` mock surface.)

> **Hidden caller — `AdminRulesController.preview()`:** `controller/AdminRulesController.java:130-134` calls `promptBuilder.build("<your question here>", List.of())` with the OLD 2-arg signature. Changing `build` to 3-arg here breaks this controller at compile time, so it MUST be updated in THIS task (Step 4b) — inject `BrainResolver`, resolve the default brain, and pass its id.

> **Sequencing note:** because `AskService` still calls `promptBuilderService.build(q, chunks)` / `validate(answer, true)` / `disclaimer()` with the OLD arity, changing those service signatures here would break `AskService` compilation. **Resolution:** in this task, ADD the `brainId` parameter to the service methods AND update the `AskService` call sites in the same task (the call sites already have `brainId` in scope from SP1), but leave the `AskService` CONSTRUCTOR on `DomainPack` (the `canned` field) for Task 7. This keeps the build green without prematurely touching the `AskService` ctor. Update `AskService.java:107,142,170,202` accordingly (see Step 3).

- [ ] **Step 1: Update the two service tests + the golden assembly test (failing first)**

`service/ai/PromptBuilderServiceTest.java`:
- Add import `import static com.msfg.rag.TestBrains.DEFAULT_ID;` (and `import com.msfg.rag.pack.TestPacks;` already present).
- Line 25-26 stubs: `when(rulesService.effectiveHard()).thenReturn(...)` → `when(rulesService.effectiveHard(DEFAULT_ID)).thenReturn(...)`; same for `effectiveGuidance(DEFAULT_ID)`. (RulesService gains a `brainId` param in Task 6; to keep THIS task green BEFORE Task 6, instead stub the still-current no-arg form here and switch to the `brainId` form in Task 6. **Decision:** keep `effectiveHard()`/`effectiveGuidance()` no-arg stubs in this task — `RulesService` is migrated in Task 6, and `PromptBuilderService.build` still calls the no-arg `rules.effectiveHard()` until Task 6. Do NOT change the RulesService stubs here.)
- Line 27: `promptBuilder = new PromptBuilderService(TestPacks.registry(), rulesService);`
- Every `promptBuilder.build(q, chunks)` call (lines 42, 51, 59, 65, 71, 77, 90) → `promptBuilder.build(q, chunks, DEFAULT_ID)`.

`service/ai/AnswerValidationServiceTest.java`:
- Add import `import static com.msfg.rag.TestBrains.DEFAULT_ID;`.
- Line 22: `private final AnswerValidationService validator = new AnswerValidationService(TestPacks.registry());`
- Every `validator.validate(answer, bool)` call (lines 34, 49, 56, 62, 70, 76, 83) → `validator.validate(answer, bool, DEFAULT_ID)`.

`pack/MsfgGoldenPackTest.java` (only `defaultAssemblyIsByteExact`, lines 212-232):
- Add import `import static com.msfg.rag.TestBrains.DEFAULT_ID;`.
- Line 218: `String assembled = new PromptBuilderService(TestPacks.registry(), rulesService).build("Q", List.of(), DEFAULT_ID);`
- The `rulesService` stubs at 214-216 stay no-arg (`effectiveHard()`/`effectiveGuidance()`) — unchanged until Task 6. The expected-string assembly at 220-225 and all other tests are untouched.

Run → compile FAIL (no registry ctor / no 3-arg `build` / no 3-arg `validate`).

- [ ] **Step 2: Implement `PromptBuilderService`**

Replace `service/ai/PromptBuilderService.java` body with:
```java
package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Builds the final prompt sent to the LLM. The template and disclaimer come
 * from the brain's pack (resolved per request via DomainPackRegistry), keeping
 * all company-specific text out of code. Hard rules and guidance are injected
 * live from {@link RulesService} (pack defaults until edited) and remain
 * compliance-critical: the model must answer only from the supplied source
 * context, never from general knowledge.
 */
@Service
public class PromptBuilderService {

    private final DomainPackRegistry registry;
    private final RulesService rules;

    public PromptBuilderService(DomainPackRegistry registry, RulesService rules) {
        this.registry = registry;
        this.rules = rules;
    }

    /** The brain's public disclaimer, appended to every website response. */
    public String disclaimer(UUID brainId) {
        return registry.bundle(brainId).pack().disclaimer();
    }

    public String build(String question, List<RetrievedChunk> chunks, UUID brainId) {
        var pack = registry.bundle(brainId).pack();
        return pack.promptTemplate().formatted(
                rules.effectiveHard(),
                rules.effectiveGuidance(),
                formatContext(chunks),
                question,
                pack.disclaimer());
    }

    /**
     * Each chunk is labeled [Source N] with its citation metadata so the model
     * can attribute statements to specific sources.
     */
    private String formatContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(no source context found)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("[Source ").append(n++).append("]\n");
            sb.append("source_name: ").append(chunk.sourceName()).append('\n');
            sb.append("document_name: ").append(chunk.documentName()).append('\n');
            if (chunk.section() != null) {
                sb.append("section: ").append(chunk.section()).append('\n');
            }
            if (chunk.pageNumber() != null) {
                sb.append("page_number: ").append(chunk.pageNumber()).append('\n');
            }
            if (chunk.effectiveDate() != null) {
                sb.append("effective_date: ").append(chunk.effectiveDate()).append('\n');
            }
            sb.append("content:\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
```
(`rules.effectiveHard()`/`effectiveGuidance()` stay no-arg here — they become `(brainId)` in Task 6, where this `build` body is updated to pass `brainId`.)

- [ ] **Step 3: Implement `AnswerValidationService`**

Replace `service/ai/AnswerValidationService.java` ctor + fields + `validate` signature:
```java
package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPackRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Compliance gate that runs on every model answer before it reaches the
 * website. An answer that fails here is never shown to the visitor —
 * the caller returns the escalation response instead. The prohibited-phrase
 * and eligible-phrase lists are resolved per brain from DomainPackRegistry.
 *
 * COMPLIANCE-CRITICAL: phrase lists come from the domain pack (guardrails
 * section). Additions to the pack are fine; removals need review.
 */
@Service
public class AnswerValidationService {

    private final DomainPackRegistry registry;

    public AnswerValidationService(DomainPackRegistry registry) {
        this.registry = registry;
    }

    public ValidationResult validate(ModelAnswer answer, boolean evidenceWasSufficient, UUID brainId) {
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return ValidationResult.fail("Model returned an empty answer");
        }

        var guardrails = registry.bundle(brainId).pack().guardrails();
        List<String> prohibitedPhrases = guardrails.prohibitedPhrases();
        String eligiblePhrase = guardrails.eligiblePhrase();

        String lower = answer.answer().toLowerCase(Locale.US);

        for (String phrase : prohibitedPhrases) {
            if (lower.contains(phrase)) {
                return ValidationResult.fail("Prohibited phrase detected: \"" + phrase + "\"");
            }
        }

        if (lower.contains(eligiblePhrase) && !isQuoted(answer.answer(), eligiblePhrase)) {
            return ValidationResult.fail("\"You are eligible\" used outside a direct guideline quote");
        }

        if (evidenceWasSufficient
                && (answer.citations() == null || answer.citations().isEmpty())) {
            return ValidationResult.fail("Answer is missing citations");
        }

        return ValidationResult.pass();
    }

    private boolean isQuoted(String text, String phrase) {
        String lower = text.toLowerCase(Locale.US);
        int idx = lower.indexOf(phrase);
        while (idx >= 0) {
            boolean openQuoteBefore = text.lastIndexOf('"', idx) >= 0
                    || text.lastIndexOf('“', idx) >= 0;
            int end = idx + phrase.length();
            boolean closeQuoteAfter = text.indexOf('"', end) >= 0
                    || text.indexOf('”', end) >= 0;
            if (!(openQuoteBefore && closeQuoteAfter)) {
                return false;
            }
            idx = lower.indexOf(phrase, end);
        }
        return true;
    }

    public record ValidationResult(boolean valid, String failureReason) {

        static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
```
> **Verbatim preservation:** the curly-quote literals `'“'` / `'”'` are the same `'“'`/`'”'` (left/right double quotation marks) used in the original at lines 59, 62 — keep them. (You may instead keep the raw `'“'`/`'”'` glyphs from the original file if your editor preserves them; the escape form is given to avoid any encoding ambiguity. Either is byte-equivalent at runtime.)

- [ ] **Step 4: Thread `brainId` into the `AskService` call sites for build/validate/disclaimer**

In `service/AskService.java` (`ask` has `brainId` in scope from SP1):
- Line 107: `String prompt = promptBuilderService.build(request.question(), retrieval.chunks(), brainId);`
- Line 142: `var validation = answerValidationService.validate(modelAnswer, true, brainId);`
- Line 170 (in the success return) `promptBuilderService.disclaimer()` → `promptBuilderService.disclaimer(brainId)`.
- Line 202 (in `refuse`): `promptBuilderService.disclaimer()` → `promptBuilderService.disclaimer(brainId)`. **`refuse` does not currently take `brainId`** — add a `UUID brainId` parameter to `refuse(...)` (187-203) and pass `brainId` at both call sites (lines 93-94 category refusal, 102-103 no-source, 114-115 unparseable, 125-126 escalation-no-citation, 145-146 validation-fail). All those call sites are inside `ask`, where `brainId` is in scope. (The `AskService` ctor still injects `DomainPack pack` for `canned` — migrated in Task 7.)

- [ ] **Step 4b: Fix `AdminRulesController.preview()` (the other `build` caller)**

`controller/AdminRulesController.java`:
- Inject `BrainResolver`: add `import com.msfg.rag.service.BrainResolver;`, add a `private final BrainResolver brainResolver;` field, and add it to the ctor — `public AdminRulesController(RulesService rulesService, PromptBuilderService promptBuilder, BrainResolver brainResolver)` (set `this.brainResolver = brainResolver;`).
- `preview()` (130-134): change
  ```java
  String prompt = promptBuilder.build("<your question here>", List.of());
  ```
  to
  ```java
  String prompt = promptBuilder.build(
          "<your question here>", List.of(), brainResolver.resolve(null).getId());
  ```
  (resolves the default brain since `/preview` has no brain selector yet; a later phase adds one.)

`controller/AdminRulesControllerTest.java` (verified: `rulesService` + `promptBuilder` are BOTH `mock(...)` at lines 18-19; single construction `new AdminRulesController(rulesService, promptBuilder)` at line 21):
- Add a `BrainResolver` mock field + stub: `private final BrainResolver brainResolver = mock(BrainResolver.class);` and in setup `when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mountain State Financial Group"));`.
- Line 21: `new AdminRulesController(rulesService, promptBuilder, brainResolver)`.
- The `preview` test stub at line 142, `when(promptBuilder.build(anyString(), eq(List.of()))).thenReturn("<<built prompt>>");` → `when(promptBuilder.build(anyString(), eq(List.of()), any())).thenReturn("<<built prompt>>");`.
- The `when(rulesService.state())` stubs at lines 31, 50, 91 stay as-is FOR NOW (Task 5 has not yet changed `state`'s signature) — they are updated to `state(any())` in Task 6 Step 5. Add imports `com.msfg.rag.service.BrainResolver`, `com.msfg.rag.domain.Brain`, `com.msfg.rag.TestBrains`, `static org.mockito.ArgumentMatchers.any`.

- [ ] **Step 5: Update `AskServiceTest` + `AskServiceBrainTest` mock stubs for the new arities**

`service/AskServiceTest.java`:
- Line 78: `when(promptBuilder.build(anyString(), anyList())).thenReturn("PROMPT");` → `when(promptBuilder.build(anyString(), anyList(), any())).thenReturn("PROMPT");`
- Line 79: `when(promptBuilder.disclaimer()).thenReturn("pack-disclaimer");` → `when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");`
- Line 109: `when(promptBuilder.build(anyString(), anyList())).thenReturn("PROMPT");` → `... , any())).thenReturn("PROMPT");`
- Line 110: `when(promptBuilder.disclaimer()).thenReturn("pack-disclaimer");` → `disclaimer(any())`.
- Both `askServiceReturning`/`askServiceClassifying` construct `new AnswerValidationService(TestPacks.msfg())` (lines 96, 124) — change to `new AnswerValidationService(TestPacks.registry())`. (These are REAL validators, not mocks; the registry fixture maps `DEFAULT_ID` → msfg, but `ask` is invoked with `TestBrains.DEFAULT_ID` in this test, so `validate(..., DEFAULT_ID)` resolves correctly.) `any` already imported.

`service/AskServiceBrainTest.java`:
- Line 62: `when(promptBuilder.disclaimer()).thenReturn("pack-disclaimer");` → `when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");`
- Line 73: `new AnswerValidationService(TestPacks.msfg())` → `new AnswerValidationService(TestPacks.registry())`. **CAUTION:** this test invokes `ask(request(null), BRAIN_A)` / `BRAIN_B` where `BRAIN_A = ...000a`, `BRAIN_B = ...000b`. The real `AnswerValidationService` would call `registry.bundle(BRAIN_A)`, which is NOT preloaded in `TestPacks.registry()` (only `DEFAULT_ID` is) → it would hit the mocked `BrainRepository` and throw. **But** `AskServiceBrainTest` always uses `RetrievalResult.empty()` (insufficient evidence, line 61), so `ask` takes the early no-source refusal path and NEVER reaches `answerValidationService.validate` (which is only called at line 142, after sufficient evidence). So the validator is constructed but never invoked → safe. The `promptBuilder` is a mock (line 50), so `build`/`disclaimer` are stubbed, not real. Confirm by running: these four brain tests stay green.

> **If a future change made `AskServiceBrainTest` reach `validate`,** preload BRAIN_A/BRAIN_B into a registry via `TestPacks.registryFor(BRAIN_A, TestPacks.msfg())` etc. Not needed now (documented for the implementer).

- [ ] **Step 6: Run → PASS + commit**

`./gradlew test` → green (golden `defaultAssemblyIsByteExact` still byte-exact via the registry; compliance validator tests pass with `DEFAULT_ID`). Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: PromptBuilderService + AnswerValidationService resolve pack per brain

Both inject DomainPackRegistry and take brainId: build(q,chunks,brainId),
disclaimer(brainId), validate(answer,sufficient,brainId) read the brain's
template/disclaimer and guardrail phrase lists from the bundle. AskService
threads brainId into build/validate/disclaimer (refuse() gains brainId).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Migrate `RulesService` pack-default fallback to the registry (per-brain default; DB cache stays global)

`RulesService.effectiveHard/effectiveGuidance/state` use `pack.hardRules()/guidance()` as the fallback DEFAULT. Make that fallback per-brain by resolving the brain's pack from the registry; **the `rule_revisions` DB cache stays global** (keyed by `rules.hard`/`rules.guidance`; the table has no `brain_id`). Per-brain rule EDITING is deferred to a later sub-plan.

**Files:** modify `service/ai/RulesService.java`, `service/ai/PromptBuilderService.java` (thread `brainId` into the `rules.effective*` calls), `controller/AdminRulesController.java` (the three `rulesService.state()` calls); modify tests `service/ai/RulesServiceTest.java`, `service/ai/PromptBuilderServiceTest.java`, `pack/MsfgGoldenPackTest.java` (the `effectiveHard()/effectiveGuidance()` stubs), `controller/AdminRulesControllerTest.java`. The ONLY non-`PromptBuilder` caller of `state()` is `AdminRulesController` (verified: `state()` at lines 53, 75, 89; no `RulesController` exists). `AdminRulesController` already injects `BrainResolver` from Task 5 Step 4b, so reuse it here.

- [ ] **Step 1: Grep the `RulesService` call surface**

```bash
cd /Users/zacharyzink/rag-brain && rg -n "rules\.(effectiveHard|effectiveGuidance|state)\(|rulesService\.(effectiveHard|effectiveGuidance|state)\(" src/main
```
Expected callers (verified): `PromptBuilderService.build` (`rules.effectiveHard()`/`effectiveGuidance()` at lines 36-37) and `AdminRulesController` (`rulesService.state()` at lines 53, 75, 89). There is NO `effectiveHard()`/`effectiveGuidance()` caller outside `PromptBuilderService`. Each site gets a `brainId` (the default-brain id where the caller has no brain context, via `BrainResolver`).

- [ ] **Step 2: Update `RulesServiceTest` (failing first)**

In `service/ai/RulesServiceTest.java`:
- Add import `import static com.msfg.rag.TestBrains.DEFAULT_ID;`.
- Line 31: `return new RulesService(repo, TestPacks.registry());`
- `s.effectiveHard()` → `s.effectiveHard(DEFAULT_ID)` (lines 41, 60, 82, 98, 108, 120, 122); `s.effectiveGuidance()` → `s.effectiveGuidance(DEFAULT_ID)` (42, 121, 123); `s.state()` → `s.state(DEFAULT_ID)` (44, 61, 62, 85). `save`/`revert`/`history`/`invalidate` are GLOBAL — NO `brainId` (the `save("rules.hard", ...)` / `revert(...)` calls at 101, 138, 142, 155 stay unchanged; the `KEYS` validation is global).

Run → compile FAIL (no registry ctor / no `brainId` accessors).

- [ ] **Step 3: Implement `RulesService`**

In `service/ai/RulesService.java`:
- Swap the import `import com.msfg.rag.pack.DomainPack;` → `import com.msfg.rag.pack.DomainPackRegistry;`; add `import java.util.UUID;`.
- Field (41): `private final DomainPack pack;` → `private final DomainPackRegistry registry;`.
- Ctor (48-51): `public RulesService(RuleRevisionRepository repo, DomainPackRegistry registry) { this.repo = repo; this.registry = registry; }`.
- `effectiveHard()` (55-57) → `public String effectiveHard(UUID brainId) { return effective("rules.hard", registry.bundle(brainId).pack().hardRules()); }`.
- `effectiveGuidance()` (59-61) → `public String effectiveGuidance(UUID brainId) { return effective("rules.guidance", registry.bundle(brainId).pack().guidance()); }`.
- `state()` (64-69) → `public Map<String, RuleState> state(UUID brainId) {` with `var pack = registry.bundle(brainId).pack();` then use `pack.hardRules()`/`pack.guidance()` as the two pack defaults (replace the two `pack.hardRules()`/`pack.guidance()` references at 67-68 with the local `pack`).
- `private String effective(String key, String packDefault)` (106-112) and `toState`, `snapshot`, `save`, `revert`, `invalidate`, `history`, `requireKnownKey`, `KEYS`, the cache fields — ALL unchanged (the DB layer is global). Only the three public accessors gain `brainId` and source their pack default from the registry.

- [ ] **Step 4: Update `PromptBuilderService.build` to pass `brainId` to RulesService**

In `service/ai/PromptBuilderService.java`, `build(String question, List<RetrievedChunk> chunks, UUID brainId)` (from Task 5): change `rules.effectiveHard()` / `rules.effectiveGuidance()` to `rules.effectiveHard(brainId)` / `rules.effectiveGuidance(brainId)`.

- [ ] **Step 5: Update the three `AdminRulesController.state()` call sites**

`controller/AdminRulesController.java` already injects `BrainResolver` (Task 5 Step 4b). Change all three `rulesService.state()` calls (line 53 in `getState`, line 75 in `putRule`, line 89 in `revertRule`) to `rulesService.state(brainResolver.resolve(null).getId())` (the default brain — `/api/ai/admin/rules` has no brain selector yet; a later phase adds one). No new field/import needed (BrainResolver already present from Task 5 Step 4b). The `save`/`revert`/`history` calls stay unchanged (global).

`controller/AdminRulesControllerTest.java`: change the three `when(rulesService.state())...` stubs (lines 31, 50, 91) to `when(rulesService.state(any()))...`. The `BrainResolver` mock + construction were already added in Task 5 Step 4b, so `state(DEFAULT_ID)` resolves through it.

- [ ] **Step 6: Update `PromptBuilderServiceTest` + `MsfgGoldenPackTest` RulesService stubs**

`service/ai/PromptBuilderServiceTest.java` lines 25-26 and `pack/MsfgGoldenPackTest.java` lines 214-216: change the no-arg stubs to the `brainId` form:
- `when(rulesService.effectiveHard()).thenReturn(...)` → `when(rulesService.effectiveHard(DEFAULT_ID)).thenReturn(...)`.
- `when(rulesService.effectiveGuidance()).thenReturn(...)` → `when(rulesService.effectiveGuidance(DEFAULT_ID)).thenReturn(...)`.
- `PromptBuilderServiceTest` line 89 (`customHardRulesReachThePrompt`): `when(rulesService.effectiveHard()).thenReturn("ONLY ANSWER IN HAIKU.");` → `when(rulesService.effectiveHard(DEFAULT_ID)).thenReturn("ONLY ANSWER IN HAIKU.");`.
- Add `import static com.msfg.rag.TestBrains.DEFAULT_ID;` to `MsfgGoldenPackTest` if not already added in Task 5 (Task 5 added it). In `PromptBuilderServiceTest`, `DEFAULT_ID` is already imported from Task 5.
(All `build(q, chunks, DEFAULT_ID)` calls already updated in Task 5; the assembly assertion remains byte-exact because the stubs still return the pack's real hard/guidance text.)

- [ ] **Step 7: Run → PASS + commit**

`./gradlew test` → green. Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: RulesService pack-default fallback resolves per brain

effectiveHard/effectiveGuidance/state take brainId and source their pack
default from DomainPackRegistry. The rule_revisions DB cache stays GLOBAL
(no brain_id on the table) — per-brain rule editing is deferred to a later
sub-plan. PromptBuilder threads brainId into the rules accessors.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Migrate `AskService` + `AdminStatsController`, then remove the `DomainPack @Bean` (FINAL — ATOMIC)

The last two `DomainPack` injectors. `AskService` resolves `canned` per request; `AdminStatsController` resolves the BrainDto per brain (corpus counts stay global — SP4). Once both no longer inject `DomainPack`, remove the `@Bean` in the SAME task so nothing is left injecting a deleted bean.

**Files:** modify `service/AskService.java`, `controller/AdminStatsController.java`, `config/DomainPackConfig.java`; modify tests `service/AskServiceTest.java`, `service/AskServiceBrainTest.java`, `controller/AdminStatsControllerTest.java`.

- [ ] **Step 1: Update `AskServiceTest`, `AskServiceBrainTest`, `AdminStatsControllerTest` (failing first)**

`service/AskServiceTest.java` (lines 95, 123): `new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router, new AnswerValidationService(TestPacks.registry()), audit, conversations, messages, sources, new ObjectMapper())` → replace `TestPacks.msfg()` (the FIRST arg) with `TestPacks.registry()`. (The `AnswerValidationService(TestPacks.registry())` arg already updated in Task 5.) The `.ask(pmiQuestion(), TestBrains.DEFAULT_ID)` calls are unchanged — `DEFAULT_ID` is preloaded, so `canned` resolves to the msfg pack.

`service/AskServiceBrainTest.java` (line 72): `new AskService(TestPacks.msfg(), ...)` → `new AskService(TestPacks.registry(), ...)`. **CAUTION:** this test invokes `ask(..., BRAIN_A)` / `BRAIN_B`, neither preloaded. `AskService` now resolves `canned` via `registry.bundle(brainId).pack().guardrails().cannedAnswers()` — but ONLY inside `categoryAnswer`, which is reached only on a non-EDUCATIONAL classification. `AskServiceBrainTest` classifies EVERYTHING as `EDUCATIONAL` (line 58) and uses `RetrievalResult.empty()`, so it takes the no-source refusal path (`canned.noSource()`), which DOES resolve `canned` for `BRAIN_A`/`BRAIN_B`. **Therefore preload both brains in this test:** replace `TestPacks.registry()` with a registry preloaded with BRAIN_A and BRAIN_B:
```java
    private DomainPackRegistry brainRegistry() {
        DomainPackRegistry r = TestPacks.registryFor(BRAIN_A, TestPacks.msfg());
        // also map BRAIN_B and DEFAULT to the same pack for this test
        r.preload(BRAIN_B, com.msfg.rag.pack.BrainPackBundle.of(TestPacks.msfg()));
        return r;
    }
```
> `preload` is package-private in `com.msfg.rag.pack`; `AskServiceBrainTest` is in `com.msfg.rag.service`, so it CANNOT call `preload` directly. **Resolution:** add a `TestPacks.registryFor(Map<UUID,DomainPack>)` overload (in `com.msfg.rag.pack`) that preloads multiple brains, and call it from the test:
> ```java
>   // in TestPacks:
>   public static DomainPackRegistry registryFor(java.util.Map<java.util.UUID, DomainPack> packs) {
>       DomainPackRegistry registry = new DomainPackRegistry(
>               org.mockito.Mockito.mock(com.msfg.rag.repository.BrainRepository.class));
>       packs.forEach((id, p) -> registry.preload(id, BrainPackBundle.of(p)));
>       return registry;
>   }
>   // in AskServiceBrainTest.askService():
>   var registry = TestPacks.registryFor(java.util.Map.of(BRAIN_A, TestPacks.msfg(), BRAIN_B, TestPacks.msfg()));
>   return new AskService(registry, classifier, retrieval, promptBuilder, router, ...);
> ```
> Add this overload to `TestPacks` in Step 3 below.

`controller/AdminStatsControllerTest.java`:
- Add imports: `import com.msfg.rag.service.BrainResolver;`, `import com.msfg.rag.domain.Brain;`, `import com.msfg.rag.TestBrains;`, and `import static org.mockito.ArgumentMatchers.any;`.
- Build a `BrainResolver` mock returning a default brain:
```java
        BrainResolver resolver = mock(BrainResolver.class);
        Brain defaultBrain = new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mountain State Financial Group");
        when(resolver.resolve(any())).thenReturn(defaultBrain);
        AdminStatsController controller =
                new AdminStatsController(TestPacks.registry(), resolver, docs, chunks);
        AdminStatsController.StatsDto stats = controller.stats(null);
```
- The brand-name assertion (`stats.brain().companyName()` == "Mountain State Financial Group") now comes from the PACK via the registry (`registry.bundle(DEFAULT_ID).pack().companyName()`), so it stays "Mountain State Financial Group"; `slug()` stays "mortgage". Corpus-count assertions unchanged.

Run → compile FAIL.

- [ ] **Step 2: Implement `AskService`**

In `service/AskService.java`:
- Swap import `import com.msfg.rag.pack.DomainPack;` → `import com.msfg.rag.pack.DomainPackRegistry;`.
- DELETE field `private final DomainPack.CannedAnswers canned;` (47); ADD `private final DomainPackRegistry packRegistry;`.
- Ctor (60-82): replace first param `DomainPack pack` with `DomainPackRegistry packRegistry`; replace `this.canned = pack.guardrails().cannedAnswers();` (71) with `this.packRegistry = packRegistry;`.
- In `ask` (85): resolve `canned` once per request near the top (after `resolveConversation`, before classification):
  ```java
  DomainPack.CannedAnswers canned = packRegistry.bundle(brainId).pack().guardrails().cannedAnswers();
  ```
  (Add `import com.msfg.rag.pack.DomainPack;` back for the `DomainPack.CannedAnswers` type — you need BOTH imports; or fully-qualify `com.msfg.rag.pack.DomainPack.CannedAnswers`. Simplest: keep `import com.msfg.rag.pack.DomainPack;` AND add `import com.msfg.rag.pack.DomainPackRegistry;`.) The existing `categoryAnswer(category, canned)` call (94) and the `canned.noSource()`/`canned.escalation()` reads (102, 114, 125, 145) now reference the local `canned`.
- `categoryAnswer(QuestionCategory category, DomainPack.CannedAnswers canned)` (175-185) — unchanged signature (still takes `canned` as a param).
- The `refuse(...)` method already takes `brainId` from Task 5 — unchanged here.

- [ ] **Step 3: Implement `AdminStatsController` + add the `TestPacks` multi-brain overload**

`controller/AdminStatsController.java`:
```java
package com.msfg.rag.controller;

import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.BrainResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Brain identity + corpus counts for the dashboard shell and corpus screen. */
@RestController
@RequestMapping("/api/ai/admin/stats")
public class AdminStatsController {

    public record BrainDto(String companyName, String slug) {}
    public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
    public record StatsDto(BrainDto brain, CorpusDto corpus) {}

    private final DomainPackRegistry packRegistry;
    private final BrainResolver brainResolver;
    private final MortgageDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public AdminStatsController(DomainPackRegistry packRegistry,
                                BrainResolver brainResolver,
                                MortgageDocumentRepository documents,
                                DocumentChunkRepository chunks) {
        this.packRegistry = packRegistry;
        this.brainResolver = brainResolver;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public StatsDto stats(@RequestParam(value = "brain", required = false) String brain) {
        var resolved = brainResolver.resolve(brain);
        var pack = packRegistry.bundle(resolved.getId()).pack();
        // Corpus counts stay global for now; SP4 scopes them by brain_id.
        return new StatsDto(
                new BrainDto(pack.companyName(), pack.slug()),
                new CorpusDto(documents.countByActiveTrue(), documents.count(), chunks.count()));
    }
}
```

Add the multi-brain `TestPacks` overload (from Step 1's resolution) to `src/test/java/com/msfg/rag/pack/TestPacks.java`:
```java
    /** A registry preloaded with several brain ids → packs. */
    public static DomainPackRegistry registryFor(java.util.Map<java.util.UUID, DomainPack> packs) {
        DomainPackRegistry registry = new DomainPackRegistry(
                org.mockito.Mockito.mock(com.msfg.rag.repository.BrainRepository.class));
        packs.forEach((id, p) -> registry.preload(id, BrainPackBundle.of(p)));
        return registry;
    }
```

- [ ] **Step 4: Remove the `DomainPack @Bean` (now safe — no injectors remain)**

In `config/DomainPackConfig.java`, DELETE the entire `domainPack(...)` `@Bean` method (24-35) and its now-unused imports (`DomainPack`, `DomainPackLoader`, `Path`, `@Value`, `@Bean`). The class becomes empty of beans — **DELETE the whole file** `config/DomainPackConfig.java` (no remaining beans; the registry + loader supersede it). The slug-match guard the bean enforced is now enforced per brain inside `DomainPackRegistry.load`.

> Verify nothing else references `DomainPackConfig`: `rg -n "DomainPackConfig" src` — expect no hits after deletion. If a test references it, remove that reference (none expected — grep at Step 5).

- [ ] **Step 5: Verify no `DomainPack`-bean injection remains**

```bash
cd /Users/zacharyzink/rag-brain && rg -n "DomainPack " src/main/java/com/msfg/rag --glob '!pack/DomainPack*.java'
```
Expect only `DomainPack.CannedAnswers` (a local type use in `AskService`) and `DomainPack.ProgramRule` (in `CompiledProgram`) — NO constructor parameter `DomainPack pack` and NO `@Bean public DomainPack`. Confirm `rg -n "@Bean" src/main/java/com/msfg/rag/config/DomainPackConfig.java` returns nothing (file deleted).

- [ ] **Step 6: Run → PASS + commit**

`./gradlew test` → green (the FULL suite, including `MsfgGoldenPackTest` unchanged in intent, the compliance validator + classifier tests, and the registry tests). Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3b: migrate AskService + AdminStats to registry; drop DomainPack @Bean

AskService resolves canned answers per brain from the bundle; AdminStats
resolves the BrainDto per brain (BrainResolver + registry), corpus counts
left global (SP4). With the last two injectors migrated, the single
DomainPack @Bean is removed (DomainPackConfig deleted) — every brain now runs
its own pack via DomainPackRegistry. Single-brain behavior is unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Regression + boot verification

- [ ] **Step 1:** `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL`. Golden-pack, compliance (classifier + validator), registry, and all repository/integration tests pass.
- [ ] **Step 2:** Boot on 8090 (background) and confirm a clean startup + an ask works end to end against the default brain (proves `DefaultBrainSeeder` set `packRef`, the registry lazy-loads the default brain's pack on the first request, and classify→retrieve→build→validate→disclaimer all resolve per brain), then stop + `docker compose down`:
```bash
docker compose up -d && set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090'
# then, in another shell:
# curl -s -X POST 'http://localhost:8090/api/ai/mortgage/ask' -H 'Content-Type: application/json' \
#   -d '{"sessionId":"p3b","question":"Can I use gift funds for my down payment?","loanType":"conventional","state":"CO"}'
# and: curl -s 'http://localhost:8090/api/ai/admin/stats' -H 'X-Admin-Api-Key: <key from .env>'
```
Expect a structured JSON ask response (low-confidence refusal if the corpus is empty) and a stats response whose `brain.companyName` == "Mountain State Financial Group" — both sourced from the registry's lazily-loaded default bundle.
- [ ] **Step 3: Gate:** no `DomainPack @Bean` remains; every pack-consuming service resolves its pack per `brainId` via `DomainPackRegistry`; the default brain's bundle reproduces today's pack (golden + compliance suites green); full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/` (source project untouched).

---

## Self-Review

- **Spec coverage (§8, "Pack registry"):** the single immutable `DomainPack` bean is replaced by `DomainPackRegistry` (Task 2, removed Task 7); the two per-brain precomputes (`QuestionClassifierService` compiled regex, `RetrievalService` compiled programs + acronym map) move into `BrainPackBundle` built per brain (Task 2) and are consumed per `brainId` (Tasks 3-4); consumers resolve the brain's pack from the registry by the SP1 explicit `brainId` carrier (Tasks 3-7), not a request-scoped/ThreadLocal context; new/edited brains load+validate fail-fast into the registry (the slug guard preserved in `load`, `reload` for Phase 6). The V7 DEFAULT and `rule_revisions` brain-scoping are correctly DEFERRED (noted below + in the RulesService task).
- **Atomicity honored:** the `DomainPack @Bean` is removed ONLY in Task 7, after all 7 consumers migrated (each intermediate task green because both the bean and registry coexist and the default brain's bundle == today's pack). No intermediate task leaves a deleted bean still injected.
- **Single-brain parity:** every consumer test passes `TestBrains.DEFAULT_ID`, and `TestPacks.registry()` maps `DEFAULT_ID` → the real msfg pack — so `MsfgGoldenPackTest.defaultAssemblyIsByteExact`, the classifier guardrail tests, and the validator compliance tests assert the SAME literals through the registry. `MsfgGoldenPackTest`'s pack-content assertions are untouched (it still loads the pack directly via `TestPacks.msfg()`).
- **Type/signature consistency:** `bundle(UUID)` / `reload(UUID)` / `preload(UUID, BrainPackBundle)`; `classify(String, UUID)`; `retrieve(String, UUID)` (already SP1); `build(String, List<RetrievedChunk>, UUID)`; `disclaimer(UUID)`; `validate(ModelAnswer, boolean, UUID)`; `effectiveHard(UUID)`/`effectiveGuidance(UUID)`/`state(UUID)`; `AskService` ctor first arg `DomainPackRegistry`; `AdminStatsController(DomainPackRegistry, BrainResolver, MortgageDocumentRepository, DocumentChunkRepository)` + `stats(String brain)`. `CompiledProgram` is `com.msfg.rag.pack.CompiledProgram` with `static compile(List<DomainPack.ProgramRule>)`. `BrainPackBundle` exposes `pack()`, `classifierPatterns()` (order-stable `LinkedHashMap` wrapped unmodifiable), `programs()`, `acronyms()`.
- **No circular dependency:** `CompiledProgram` + `BrainPackBundle` + `DomainPackRegistry` live in `com.msfg.rag.pack` and depend only on `DomainPack`, `QuestionCategory`, `Brain`, `BrainRepository` (none of which depend back on the consumer services). `RetrievalService` (in `service.retrieval`) imports `pack.CompiledProgram`/`pack.BrainPackBundle`/`pack.DomainPackRegistry` one-directionally.
- **Placeholder scan:** every edit cites a file + line range. Complete code is given for the 3 new production types (`CompiledProgram`, `BrainPackBundle`, `DomainPackRegistry`) and full replacement class bodies for `QuestionClassifierService`, `PromptBuilderService`, `AnswerValidationService`, `AdminStatsController`; `RetrievalService`/`RulesService`/`AskService`/`AdminRulesController` get precise per-line edits (field/ctor/call-site) because their bodies are large and mostly unchanged. No "similar to above". The one runtime nuance (classifier check-order: `Map.copyOf` does NOT preserve order) is called out and resolved to `Collections.unmodifiableMap(LinkedHashMap)`.
- **Source project untouched:** all paths are under `/Users/zacharyzink/rag-brain`; the gate (Task 8 Step 3) re-asserts the msfg-rag working tree is unchanged.

## Deferred to later sub-plans (out of scope here — documented)

- **`rule_revisions` brain-scoping:** `RulesService`'s DB-backed custom-rule cache is keyed by GLOBAL keys (`rules.hard`/`rules.guidance`) and `rule_revisions` has no `brain_id`. This plan makes only the PACK-DEFAULT fallback per-brain; per-brain rule EDITING (a brain editing its own hard rules/guidance) requires a migration adding `brain_id` to `rule_revisions` + brain-keyed cache + brain-scoped `save`/`revert`/`history`/`state` — a dedicated later sub-plan.
- **AdminStats corpus counts → SP4:** `countByActiveTrue()`/`count()`/`chunks.count()` stay GLOBAL in `AdminStatsController.stats`. SP4 (admin read-scoping) replaces them with brain-scoped queries (`findByBrainId...`) once multi-brain ingestion lands.
- **V8 drop-default → SP3:** the V7 `brain_id` column DEFAULT (`00000000-0000-0000-0000-000000000001`) is retained; `ALTER TABLE ... ALTER COLUMN brain_id DROP DEFAULT` happens in SP3/V8 after SP1/SP2/SP4 are verified.
