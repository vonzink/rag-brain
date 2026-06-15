# rag-brain Phase 3a (SP1) — Brain-Aware Request Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the ask + ingestion request path brain-aware: resolve the brain once at the controller (default brain, or `?brain=<slug>` override), thread its `brain_id` explicitly through retrieval (as a filter) and every write (so each persisted row is stamped with its brain) — with single-brain behavior unchanged.

**Architecture:** Phase 3 of the co-resident `brain_id` design (spec `docs/superpowers/specs/2026-06-14-rag-brain-multi-brain-design.md` §8), split into sub-plans by a verification workflow. This is **SP1** — the core. The V7 column `DEFAULT` is **retained** (dropped later in SP3/V8). `brain_id` is threaded as an **explicit method parameter** (no request-scoped bean / no ThreadLocal) because `AuditLogService.record` runs `@Transactional(REQUIRES_NEW)` on a separate connection where ambient context is unreliable. **Out of scope (later sub-plans):** `DomainPackRegistry` / per-brain packs (SP2 — not needed for one brain), brain-scoping admin sync/stats *reads* (SP4), dropping the DEFAULT (SP3), `rule_revisions` brain-scoping.

**Tech Stack:** Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 + pgvector · JUnit 5 + Testcontainers. **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

---

## Context the engineer needs (verified, with exact locations)

**The atomicity rule (BLOCKER if violated):** once a `brainId` JPA field is *mapped* on an entity, Hibernate includes `brain_id` in every INSERT. If a writer doesn't set it, Hibernate sends `NULL`, which **overrides the V7 column DEFAULT** and violates `NOT NULL` → every insert for that table breaks. Therefore **the entity-field-add and ALL its writers' `setBrainId(...)` calls must land in the same task (Task 2).** Reads (Task 1) never map the field, so they're safe to do first.

**The six entities + their ONLY writers:**
| Entity (`@Table`) | Writer site(s) |
|---|---|
| `MortgageDocument` (`brain_documents`) | `DocumentIngestionService.ingest` (`new` at line 74, save 84) |
| `DocumentChunk` (`brain_document_chunks`) | `DocumentIngestionService.extractChunkAndEmbed` (`new` at 130, `saveAll` 155) |
| `Conversation` (`ai_conversations`) | `AskService.resolveConversation` (`new` at 213, save 216) |
| `Message` (`ai_messages`) | `AskService.saveMessage` (`new` at 221, save 231) — called from lines 86, 157, 192 |
| `AnswerSource` (`ai_answer_sources`) | `AskService.saveAnswerSources` (`new` at 236, save 246) — called from 159, 193 |
| `AuditLog` (`ai_audit_logs`) | `AuditLogService.record` (`new` at 43, save 54) — `REQUIRES_NEW` at line 31 |

`SyncService` writes documents only via `DocumentIngestionService.ingest` (SyncService.java:108-119) — so threading `brainId` into `ingest` covers it; no direct entity construction in SyncService.

**Retrieval:** `DocumentChunkRepository.searchByVector` (line 26-47) and `searchByKeyword` (54-75) are hand-written native SQL (`FROM brain_document_chunks c JOIN brain_documents d ...`). Callers: `RetrievalService.retrieve` (lines 101-102), and `HybridSearchIntegrationTest` (5 direct calls at lines 82, 90, 100, 110, 118). `RetrievalService.retrieve(String question)` (line 77) is called from `AskService.ask:97` and `DocumentAdminController.testRetrieval:119`.

**Tests that PERSIST the six entities (must set `brainId` in Task 2):** `AuditLogRepositoryTest:32`, `AdminAuditControllerTest:28` (AuditLog), `HybridSearchIntegrationTest:131,143` (MortgageDocument, DocumentChunk). `SyncServiceTest` and `SyncPlannerTest` build `MortgageDocument` as **unpersisted stubs** (mocked repos) — confirm they don't hit the DB; if they don't, no change needed there.

**Resolution primitives:** `BrainRepository.findDefaultBrain()` and `findBySlug(String)` already exist; `Brain.getId()/getSlug()/isActive()` exist. Default brain id is the V7 constant `00000000-0000-0000-0000-000000000001`.

**Why no request-scoped bean:** brain is resolved in the controller and the UUID is passed down explicitly, so nothing depends on thread/transaction context — this is what makes the `REQUIRES_NEW` audit write correct.

---

### Task 0: Green baseline

- [ ] **Step 1:** `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL` (Docker required). Red baseline → stop and report.

---

### Task 1: Brain resolution + brain-filtered retrieval (reads)

Make reads brain-aware: a `BrainResolver`, the `?brain=` param, and a `brain_id` predicate on both retrieval queries. No entity fields, no write changes.

**Files:**
- Create: `src/main/java/com/msfg/rag/service/BrainResolver.java`
- Create test: `src/test/java/com/msfg/rag/service/BrainResolverTest.java`
- Modify: `src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java` (both `@Query` + signatures)
- Modify: `src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java` (`retrieve` signature + 2 call sites)
- Modify: `src/main/java/com/msfg/rag/service/AskService.java` (`ask` signature + `retrieve` call at line 97)
- Modify: `src/main/java/com/msfg/rag/controller/AskController.java` (inject resolver, `?brain=` param)
- Modify: `src/main/java/com/msfg/rag/controller/DocumentAdminController.java` (`testRetrieval` resolves brain)
- Modify tests: `HybridSearchIntegrationTest` (5 `searchBy*` calls), `RetrievalServiceTest` (retrieve call), `AskServiceTest` (ask call)

- [ ] **Step 1: Write the failing resolver test**

`src/test/java/com/msfg/rag/service/BrainResolverTest.java`:
```java
package com.msfg.rag.service;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import org.mockito.Mockito;

class BrainResolverTest {

    private final BrainRepository brains = Mockito.mock(BrainRepository.class);
    private final BrainResolver resolver = new BrainResolver(brains);

    private Brain brain(String slug, boolean active, boolean isDefault) {
        Brain b = new Brain(UUID.randomUUID(), slug, slug);
        b.setActive(active);
        b.setDefault(isDefault);
        return b;
    }

    @Test
    void resolvesDefaultWhenNoOverride() {
        Brain def = brain("mortgage", true, true);
        when(brains.findDefaultBrain()).thenReturn(Optional.of(def));
        assertEquals(def, resolver.resolve(null));
        assertEquals(def, resolver.resolve("  "));
    }

    @Test
    void resolvesBySlugOverride() {
        Brain hr = brain("hr", true, false);
        when(brains.findBySlug("hr")).thenReturn(Optional.of(hr));
        assertEquals(hr, resolver.resolve("hr"));
    }

    @Test
    void rejectsUnknownSlug() {
        when(brains.findBySlug("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("nope"));
    }

    @Test
    void rejectsInactiveBrain() {
        when(brains.findBySlug("old")).thenReturn(Optional.of(brain("old", false, false)));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("old"));
    }
}
```

- [ ] **Step 2: Run → FAIL** (`BrainResolver` missing): `./gradlew test --tests "com.msfg.rag.service.BrainResolverTest"` → compile failure.

- [ ] **Step 3: Implement `BrainResolver`**

`src/main/java/com/msfg/rag/service/BrainResolver.java`:
```java
package com.msfg.rag.service;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;

/** Resolves which brain a request targets: an explicit ?brain=<slug>, else the default brain. */
@Service
public class BrainResolver {

    private final BrainRepository brains;

    public BrainResolver(BrainRepository brains) {
        this.brains = brains;
    }

    public Brain resolve(String brainSlug) {
        if (brainSlug != null && !brainSlug.isBlank()) {
            Brain brain = brains.findBySlug(brainSlug.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainSlug));
            if (!brain.isActive()) {
                throw new IllegalArgumentException("Brain is inactive: " + brainSlug);
            }
            return brain;
        }
        return brains.findDefaultBrain()
                .orElseThrow(() -> new IllegalStateException("No default brain configured"));
    }
}
```

- [ ] **Step 4: Run → PASS**: `./gradlew test --tests "com.msfg.rag.service.BrainResolverTest"`.

- [ ] **Step 5: Add the `brain_id` predicate to both retrieval queries**

In `DocumentChunkRepository.java`: in BOTH `searchByVector` (26-47) and `searchByKeyword` (54-75), add `AND c.brain_id = :brainId` to the `WHERE` clause (the queries are `chunk`-rooted as `c`, which has `brain_id`; the index `idx_brain_document_chunks_brain` covers it). Add a `@Param("brainId") UUID brainId` parameter to each method signature (import `java.util.UUID` if needed). Example for `searchByVector`'s WHERE (keep all existing predicates, append the brain filter):
```sql
WHERE d.is_active = TRUE
  AND (d.effective_date IS NULL OR d.effective_date <= CURRENT_DATE)
  AND (d.expiration_date IS NULL OR d.expiration_date > CURRENT_DATE)
  AND c.brain_id = :brainId
  AND c.embedding IS NOT NULL
```
(Apply the equivalent `AND c.brain_id = :brainId` to `searchByKeyword`'s WHERE. Bind as a plain UUID — no `CAST`.)

- [ ] **Step 6: Thread `brainId` through `RetrievalService.retrieve` and its callers**

In `RetrievalService.java`: change `retrieve(String question)` (line 77) to `retrieve(String question, UUID brainId)` (import `java.util.UUID`); pass `brainId` to both repo calls (lines 101-102): `chunkRepository.searchByVector(vectorLiteral, candidatePool, brainId)` and `chunkRepository.searchByKeyword(toOrQuery(expandedQuestion), candidatePool, brainId)`.

In `AskService.java`: change `ask(AskRequest request)` (line 84) to `ask(AskRequest request, UUID brainId)` (import `java.util.UUID`); at line 97 call `retrievalService.retrieve(request.question(), brainId)`. (Do NOT touch the write methods in this task.)

In `DocumentAdminController.java`: inject `BrainResolver brainResolver` (constructor + field); change `testRetrieval` (117-120) to resolve a brain and pass its id:
```java
@GetMapping("/test-retrieval")
public RetrievalResult testRetrieval(@RequestParam("question") String question,
                                     @RequestParam(value = "brain", required = false) String brain) {
    return retrievalService.retrieve(question, brainResolver.resolve(brain).getId());
}
```

- [ ] **Step 7: Wire the controller `?brain=` param**

In `AskController.java`: inject `BrainResolver brainResolver` (constructor + field). Change `ask`:
```java
@PostMapping("/ask")
public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request,
                                       @RequestParam(value = "brain", required = false) String brain) {
    UUID brainId = brainResolver.resolve(brain).getId();
    return ResponseEntity.ok(askService.ask(request, brainId));
}
```
(import `java.util.UUID`, `org.springframework.web.bind.annotation.RequestParam`.) The existing `@RequestMapping("/api/ai/${brain.slug:mortgage}")` and `RateLimitFilter`/`CorsConfig` are unchanged — `?brain=` rides the same literal path, so rate-limit/CORS still match.

- [ ] **Step 8: Fix the broken call sites in tests**

Update direct `searchBy*` calls in `HybridSearchIntegrationTest` (lines 82, 90, 100, 110, 118) to pass the default brain id as the new last arg, e.g. `chunkRepository.searchByKeyword("gift funds", 10, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))`. Update any `retrievalService.retrieve(...)` call in `RetrievalServiceTest` to pass a brain UUID, and any `askService.ask(request)` call in `AskServiceTest` to `ask(request, <uuid>)`. (The compiler lists every site; pass the default brain UUID in tests.)

- [ ] **Step 9: Write a retrieval-isolation test**

Add a test to `HybridSearchIntegrationTest` (or a new `RetrievalBrainIsolationTest` following its Testcontainers setup) that inserts chunks under two different `brain_id`s (set `brain_id` directly via native insert, or rely on the V7 DEFAULT for one and a native insert for the other) and asserts `searchByKeyword/searchByVector(..., brainA)` never returns brainB's chunk. Minimal version:
```java
// after inserting one chunk under the default brain (DEFAULT) and one under OTHER via native SQL:
var hits = chunkRepository.searchByKeyword("gift funds", 10, OTHER);
assertTrue(hits.stream().noneMatch(h -> h.getChunkId().equals(defaultBrainChunkId)));
```

- [ ] **Step 10: Run + commit**

`./gradlew test` → green. Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3a: brain resolution + brain-filtered retrieval (reads)

BrainResolver (default or ?brain= override). brain_id predicate on
searchByVector/searchByKeyword; retrieve()/ask()/testRetrieval thread the
resolved brainId. No entity/write changes yet (V7 DEFAULT retained).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Entity `brain_id` fields + all writers (ATOMIC) + conversation guard

Add the `brainId` field to all 6 entities AND set it in every writer in **one change** (see the atomicity rule). The `brainId` is already threaded into `ask`/`retrieve` from Task 1; now extend it into the write paths and `ingest`/`sync`.

**Files:**
- Modify (entities): `domain/MortgageDocument.java`, `domain/DocumentChunk.java`, `domain/Conversation.java`, `domain/Message.java`, `domain/AnswerSource.java`, `domain/AuditLog.java`
- Modify (writers): `service/AskService.java`, `service/audit/AuditLogService.java`, `service/ingestion/DocumentIngestionService.java`, `service/sync/SyncService.java`, `controller/DocumentAdminController.java`
- Modify (tests): `repository/AuditLogRepositoryTest.java`, `controller/AdminAuditControllerTest.java`, `repository/HybridSearchIntegrationTest.java`, and any other that persists the six entities (compiler/NOT-NULL will reveal them)
- New tests: `service/AskServiceBrainTest` (or extend `AskServiceTest`) for the audit-brainId + conversation-guard behavior

- [ ] **Step 1: Write the failing behavior tests**

Add tests asserting (a) the audit row written via the `REQUIRES_NEW` path carries the resolved `brainId`, and (b) `resolveConversation` creates a NEW conversation when an existing one's `brainId` differs from the resolved brain. Prefer an integration-style test (Testcontainers) for (a) since `record` is `REQUIRES_NEW`. Sketch for (b) as a focused test on the guard logic; sketch for (a):
```java
// ask(request, BRAIN_A) then read the latest ai_audit_logs row and assert brain_id == BRAIN_A
```
Place under `src/test/java/com/msfg/rag/service/`. (Use the default brain seeded by V7, plus a second brain row, as the two brains.)

- [ ] **Step 2: Run → FAIL** (no `brainId` field / setters yet).

- [ ] **Step 3: Add the `brainId` field to all 6 entities**

In EACH of `MortgageDocument`, `DocumentChunk`, `Conversation`, `Message`, `AnswerSource`, `AuditLog` add (near the `id` field), plus a getter/setter:
```java
@Column(name = "brain_id", nullable = false)
private UUID brainId;
// ...
public UUID getBrainId() { return brainId; }
public void setBrainId(UUID brainId) { this.brainId = brainId; }
```
(import `java.util.UUID` where missing. No constructor changes — all six use a no-arg `new` + setters.)

- [ ] **Step 4: Set `brainId` in the AskService writers + add the conversation guard**

In `AskService.ask` (already `ask(AskRequest, UUID brainId)` from Task 1): pass `brainId` into `resolveConversation` and `auditLogService.record`.

`resolveConversation` → `resolveConversation(AskRequest request, UUID brainId)`; set it on the new conversation and add the brain-match guard:
```java
private Conversation resolveConversation(AskRequest request, UUID brainId) {
    if (request.conversationId() != null) {
        Conversation existing = conversationRepository.findById(request.conversationId()).orElse(null);
        if (existing != null
                && Objects.equals(existing.getUserSessionId(), request.sessionId())
                && Objects.equals(existing.getBrainId(), brainId)) {   // same session AND same brain
            return existing;
        }
    }
    Conversation conversation = new Conversation();
    conversation.setUserSessionId(request.sessionId());
    conversation.setSource("website");
    conversation.setBrainId(brainId);
    return conversationRepository.save(conversation);
}
```
`saveMessage` derives from the conversation (no new param): add `message.setBrainId(conversation.getBrainId());` before save (line 231 area). `saveAnswerSources` derives from the message: add a `message` brain id — since `saveAnswerSources(Message message, ...)` has the message, set `source.setBrainId(message.getBrainId());` on each `AnswerSource` before save. Update the two `record(...)` calls (lines 162-164 success, 195 refuse) to pass `brainId` as the new first-after-conversationId argument (see Step 5). Update the `resolveConversation(request)` call at line 85 to `resolveConversation(request, brainId)`.

- [ ] **Step 5: Add `brainId` to `AuditLogService.record` (REQUIRES_NEW carrier) + set it**

Change the signature to take `UUID brainId` right after `conversationId`:
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public AuditLog record(UUID conversationId, UUID brainId, String userQuestion, /* ...unchanged... */) {
    AuditLog log = new AuditLog();
    log.setConversationId(conversationId);
    log.setBrainId(brainId);
    // ...rest unchanged...
}
```
Update BOTH call sites in `AskService` (162-164 and 195-197) to pass `brainId` as the second arg.

- [ ] **Step 6: Set `brainId` on ingested documents/chunks**

In `DocumentIngestionService.ingest`, add a `UUID brainId` parameter (last) and `document.setBrainId(brainId);` before `documentRepository.save(document)` (line 84). In `extractChunkAndEmbed`, set each chunk from the document: `entity.setBrainId(document.getBrainId());` (before adding to the list, ~line 135). `reindex` needs no brain arg — the existing `document.getBrainId()` flows into the chunks automatically.

In `DocumentAdminController.upload`: add `@RequestParam(value="brain", required=false) String brain`, resolve `UUID brainId = brainResolver.resolve(brain).getId();`, and pass `brainId` as the last arg to `ingestionService.ingest(...)`.

In `SyncService`: add a `UUID brainId` parameter to `sync(boolean dryRun, UUID brainId)`, thread it into `execute(...)` → `ingest(action, bytes, brainId)` → `ingestionService.ingest(..., brainId)`. In `DocumentAdminController.sync`, add `@RequestParam(value="brain", required=false) String brain` and call `syncService.sync(dryRun, brainResolver.resolve(brain).getId())`.

- [ ] **Step 7: Fix tests that persist the six entities**

Set `brainId` on every persisted entity in tests: `AuditLogRepositoryTest:32`, `AdminAuditControllerTest:28` (`log.setBrainId(DEFAULT)`), `HybridSearchIntegrationTest:131,143` (`doc.setBrainId(DEFAULT)`, `chunk.setBrainId(DEFAULT)`), where `DEFAULT = UUID.fromString("00000000-0000-0000-0000-000000000001")`. Update `SyncServiceTest` only if it actually persists (it mocks repos — likely just add `setBrainId` if any stub is saved; confirm by running). Update any `auditLogService.record(...)` test calls and `syncService.sync(dryRun)` calls to the new signatures.

- [ ] **Step 8: Run → PASS** (`./gradlew test`). Resolve any remaining `NOT NULL`/signature breakages by setting `brainId` at that writer/test (never by reverting the field).

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3a: stamp brain_id on all writes + conversation brain guard

Add brain_id field to the 6 entities and set it in every writer atomically
(ingest/sync, conversation/message/answer-source, and the REQUIRES_NEW audit
record via an explicit param). Conversation reuse now requires a brain match.
V7 column DEFAULT still retained.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Regression + boot verification

- [ ] **Step 1:** `./gradlew test` → `BUILD SUCCESSFUL` (golden-pack + compliance + all repository tests pass).
- [ ] **Step 2:** Boot on 8090 (background), confirm clean startup + an ask works end to end against the default brain, then stop + `docker compose down`:
```bash
docker compose up -d && set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090'
# then: curl -s -X POST 'http://localhost:8090/api/ai/mortgage/ask' -H 'Content-Type: application/json' \
#   -d '{"sessionId":"p3a","question":"Can I use gift funds for my down payment?","loanType":"conventional","state":"CO"}'
```
Expect a structured JSON response (low-confidence refusal if corpus empty) — proves classify→retrieve(brainId)→prompt→model→validate→persist(brainId)→audit(brainId) all run.
- [ ] **Step 3: Gate:** reads filtered by `brain_id`; every write stamps `brain_id`; audit carries `brain_id` through `REQUIRES_NEW`; conversation reuse is brain-guarded; full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/`.

---

## Self-Review

- **Spec coverage (§8, SP1):** brain resolution default + `?brain=` (Task 1 controller + BrainResolver), retrieval `brain_id` predicate (Task 1), `brain_id` on all 6 entities + every writer incl. the `REQUIRES_NEW` audit via explicit param (Task 2), conversation-reuse brain guard (Task 2). DEFAULT retained (dropped in SP3). DomainPackRegistry, admin read-scoping, `rule_revisions` correctly deferred (noted).
- **Atomicity honored:** entity-field-add and all `setBrainId` writers are together in Task 2; Task 1 (reads) maps no field.
- **Placeholder scan:** the per-query WHERE edit and the test call-site updates are described against exact line numbers; the implementer applies `AND c.brain_id = :brainId` + a `@Param` and passes the default UUID at test call sites (compiler-enforced).
- **Consistency:** `brainId` is `UUID`; `record(conversationId, brainId, ...)`; `retrieve(question, brainId)`; `ask(request, brainId)`; `ingest(..., brainId)`; `sync(dryRun, brainId)`; default brain id `00000000-0000-0000-0000-000000000001` used uniformly in tests.

## Notes for the rest of Phase 3

- **SP2 — DomainPackRegistry:** replace the single `DomainPack` @Bean with a per-brain registry; relocate the TWO precompute caches (`QuestionClassifierService` compiled regex, `RetrievalService` compiled programs + acronym map) into per-brain holders; `classify`/`retrieve`/`build`/`validate`/`AdminStatsController` resolve per brain. Only needed once a 2nd brain exists.
- **SP4 — admin read-scoping:** scope `SyncService` reads (`documentRepository.findAll/findByActiveTrue` → `findByBrainId...`) and `DocumentAdminController.list`/stats to the brain. No-op for one brain; required before multi-brain ingestion.
- **SP3 — V8:** `ALTER TABLE ... ALTER COLUMN brain_id DROP DEFAULT` on all 6 tables, only after SP1/SP4 verified. Decide `rule_revisions` brain-scoping (7th table) or document the shared rules layer.
