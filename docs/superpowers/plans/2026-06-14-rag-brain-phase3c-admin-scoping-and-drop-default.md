# rag-brain Phase 3c (SP4 + SP3) — Admin Read-Scoping + Drop brain_id DEFAULT

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Finish Phase 3 multi-brain correctness: (SP4) scope the remaining brain-blind admin *reads* by `brain_id`, then (SP3) drop the transitional V7 `brain_id` column DEFAULT (migration `V8`) now that every writer sets `brain_id` explicitly.

**Architecture:** Closes the co-resident `brain_id` design (spec §6, §8). All entity *writers* already set `brain_id` (Phase 3a); reads are filtered (3a retrieval) and packs are per-brain (3b). This plan scopes the last brain-blind reads (`SyncService` planning reads, `DocumentAdminController.list`, `AdminStatsController` corpus counts) and removes the DEFAULT safety net so any future writer that omits `brain_id` fails loudly instead of silently defaulting. `rule_revisions` per-brain editing stays deferred (documented). **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Tech Stack:** Java 21 · Spring Data JPA · Flyway · PostgreSQL 16 · JUnit 5 + Testcontainers.

---

## Context (verified, current state)

- `SyncService.sync(boolean dryRun, UUID brainId)` already has `brainId`. Its two brain-blind reads: `documentRepository.findAll()` (SyncService.java:46) feeds the planner; `documentRepository.findByActiveTrue()` (line 92) finds stale rows to deactivate. Both must be brain-scoped or a sync for brain A would plan against / deactivate brain B's documents.
- `DocumentAdminController.list()` (`@GetMapping`, ~line 84) does `documentRepository.findAll()`. `BrainResolver brainResolver` is already injected (Phase 3a). Add a `?brain=` param + scope.
- `AdminStatsController.stats(@RequestParam brain)` (AdminStatsController.java:36-44) already resolves the brain; only its `CorpusDto` counts are global (`documents.countByActiveTrue()`, `documents.count()`, `chunks.count()`) — there is even a `// Corpus counts stay global for now; SP4 scopes them by brain_id.` comment to remove.
- `MortgageDocumentRepository` (current) has `findByActiveTrue()`, `countByActiveTrue()`. `DocumentChunkRepository` has no count method.
- **V8 will break a Phase-2 test:** `BrainRepositoryTest.existingTableInsertWithoutBrainIdDefaultsToDefaultBrain` inserts a `brain_documents` row via native SQL *without* `brain_id` and asserts it defaults to the seeded brain. Once `V8` drops the DEFAULT, that insert raises a NOT-NULL violation — so this test must be **flipped** to assert the insert now fails (the DEFAULT bridge is intentionally gone). This is the one expected test change for SP3.
- All entity writers set `brain_id` (Phase 3a), so dropping the DEFAULT is safe — existing rows are already backfilled.

---

### Task 0: Green baseline

- [ ] `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL` (Docker required). Red → stop.

---

### Task 1 (SP4): Brain-scoped admin reads

**Files:**
- Modify: `repository/MortgageDocumentRepository.java`, `repository/DocumentChunkRepository.java`
- Modify: `service/sync/SyncService.java`, `controller/DocumentAdminController.java`, `controller/AdminStatsController.java`
- Modify tests: `service/sync/SyncServiceTest.java`, `controller/AdminStatsControllerTest.java` (+ a repository finder test)

- [ ] **Step 1: Write the failing repository test**

Add `src/test/java/com/msfg/rag/repository/MortgageDocumentBrainScopeTest.java` (mirror the Testcontainers pattern in `BrainSettingRepositoryTest`). Insert a second brain row + documents under two brains via the repository (set `brainId`), and assert the new finders isolate by brain:
```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MortgageDocumentBrainScopeTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Autowired MortgageDocumentRepository docs;
    @Autowired BrainRepository brains;

    @Test
    void findersAreScopedByBrain() {
        UUID a = TestBrains.DEFAULT_ID;                 // seeded by V7
        Brain other = brains.save(new Brain(UUID.randomUUID(), "other", "Other"));
        docs.save(doc("a-active", a, true));
        docs.save(doc("a-inactive", a, false));
        docs.save(doc("b-active", other.getId(), true));

        assertEquals(2, docs.findByBrainId(a).size());
        assertEquals(1, docs.findByBrainIdAndActiveTrue(a).size());
        assertEquals(2, docs.countByBrainId(a));
        assertEquals(1, docs.countByBrainIdAndActiveTrue(a));
        assertEquals(1, docs.countByBrainId(other.getId()));
    }
    private MortgageDocument doc(String name, UUID brainId, boolean active) {
        MortgageDocument d = new MortgageDocument();
        d.setTitle(name); d.setSourceName("s"); d.setSourceType(SourceType.EDUCATIONAL);
        d.setFileName(name + ".pdf"); d.setActive(active); d.setBrainId(brainId);
        return d;
    }
}
```
(imports: `com.msfg.rag.TestBrains`, `com.msfg.rag.domain.{Brain,MortgageDocument,SourceType}`, the Testcontainers + assertion statics.)

- [ ] **Step 2: Run → FAIL** (finders don't exist): `./gradlew test --tests "com.msfg.rag.repository.MortgageDocumentBrainScopeTest"`.

- [ ] **Step 3: Add the brain-scoped finders**

`MortgageDocumentRepository.java` — add:
```java
java.util.List<MortgageDocument> findByBrainId(UUID brainId);
java.util.List<MortgageDocument> findByBrainIdAndActiveTrue(UUID brainId);
long countByBrainId(UUID brainId);
long countByBrainIdAndActiveTrue(UUID brainId);
```
`DocumentChunkRepository.java` — add `long countByBrainId(UUID brainId);`.

- [ ] **Step 4: Run → PASS** (the repository test).

- [ ] **Step 5: Scope the consumers**

`SyncService.java`: line 46 `documentRepository.findAll()` → `documentRepository.findByBrainId(brainId)`; line 92 `documentRepository.findByActiveTrue()` → `documentRepository.findByBrainIdAndActiveTrue(brainId)`.

`DocumentAdminController.java` `list()`:
```java
@GetMapping
public List<DocumentDto> list(@RequestParam(value = "brain", required = false) String brain) {
    return documentRepository.findByBrainId(brainResolver.resolve(brain).getId())
            .stream().map(DocumentDto::from).toList();
}
```

`AdminStatsController.java` `stats()`: replace the corpus line (and delete the `// stay global` comment):
```java
UUID brainId = resolved.getId();
return new StatsDto(
        new BrainDto(pack.companyName(), pack.slug()),
        new CorpusDto(documents.countByBrainIdAndActiveTrue(brainId),
                      documents.countByBrainId(brainId),
                      chunks.countByBrainId(brainId)));
```

- [ ] **Step 6: Fix the affected unit tests**

`SyncServiceTest.java`: it mocks `MortgageDocumentRepository`. Change the stubs that drive planning/deactivation from `when(documentRepository.findAll())` → `when(documentRepository.findByBrainId(any()))` and `when(documentRepository.findByActiveTrue())` → `when(documentRepository.findByBrainIdAndActiveTrue(any()))` (import `org.mockito.ArgumentMatchers.any`). The `sync(dryRun, brainId)` calls already pass a brain id. Run `SyncServiceTest` and align every stubbed read.

`AdminStatsControllerTest.java`: update the mocked count stubs to the brain-scoped methods (`countByBrainIdAndActiveTrue`/`countByBrainId`/`chunks.countByBrainId`) and keep the asserted numbers.

- [ ] **Step 7: Run + commit**

`./gradlew test` → green. Then:
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3c (SP4): scope admin reads by brain_id

Brain-scoped document/chunk finders; SyncService planning + deactivation,
DocumentAdminController.list, and AdminStats corpus counts now filter by the
resolved brain. No-op for the single default brain.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (SP3): Drop the V7 `brain_id` DEFAULT (migration V8)

**Files:**
- Create: `src/main/resources/db/migration/V8__drop_brain_id_default.sql`
- Modify test: `src/test/java/com/msfg/rag/repository/BrainRepositoryTest.java`

- [ ] **Step 1: Flip the DEFAULT test (write the new expectation first)**

In `BrainRepositoryTest.java`, replace the `existingTableInsertWithoutBrainIdDefaultsToDefaultBrain` test with one asserting the native insert WITHOUT `brain_id` now fails (the transitional DEFAULT is gone):
```java
@Test
void insertWithoutBrainIdNowFails() {
    assertThrows(Exception.class, () -> {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO brain_documents (title, source_name, source_type, file_name) " +
                "VALUES ('T', 'S', 'educational', 'f.pdf')").executeUpdate();
        em.flush();
    });
}
```
(keep the other two tests in the file unchanged: `migrationSeedsExactlyOneDefaultBrain`, `atMostOneDefaultBrainIsAllowed`.)

- [ ] **Step 2: Run → FAIL** (DEFAULT still present, so the insert succeeds): `./gradlew test --tests "com.msfg.rag.repository.BrainRepositoryTest"`.

- [ ] **Step 3: Write `V8`**

`src/main/resources/db/migration/V8__drop_brain_id_default.sql`:
```sql
-- V8: remove the transitional brain_id column DEFAULT added in V7. Every writer
-- now sets brain_id explicitly (Phase 3a), so the safety net is no longer needed
-- and dropping it makes any future writer that omits brain_id fail loudly.
ALTER TABLE brain_documents       ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE brain_document_chunks ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_conversations      ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_messages           ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_answer_sources     ALTER COLUMN brain_id DROP DEFAULT;
ALTER TABLE ai_audit_logs         ALTER COLUMN brain_id DROP DEFAULT;

-- NOTE: brain_id stays NOT NULL. rule_revisions is intentionally NOT brain-scoped
-- in this milestone — co-resident brains share one owner-editable rules layer;
-- per-brain rule editing (a brain_id on rule_revisions) is deferred.
```

- [ ] **Step 4: Run → PASS**: `./gradlew test --tests "com.msfg.rag.repository.BrainRepositoryTest"` (the insert now fails as asserted). Then run the FULL suite `./gradlew test` — it must stay green, proving every real writer sets `brain_id` (no code path relied on the DEFAULT).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 3c (SP3): drop transitional brain_id DEFAULT (V8)

All writers set brain_id explicitly, so remove the V7 column DEFAULT safety
net; brain_id stays NOT NULL. rule_revisions per-brain editing deferred.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Regression + boot verification

- [ ] **Step 1:** `./gradlew test` → `BUILD SUCCESSFUL` (golden-pack + compliance + all repository/service tests green).
- [ ] **Step 2:** Boot on 8090 (background): confirm Flyway applies `V8` cleanly, app starts, an ask against the default brain still returns a structured response, then stop + `docker compose down`:
```bash
docker compose up -d && set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090'
```
- [ ] **Step 3: Gate:** admin reads (sync/list/stats) filter by brain_id; `V8` applied; the DEFAULT is gone (the flipped test passes); full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/`.

---

## Self-Review

- **Spec coverage:** SP4 — `SyncService` reads (46, 92), `DocumentAdminController.list`, `AdminStats` counts all brain-scoped via new finders (Task 1). SP3 — `V8` drops the DEFAULT on all 6 tables, `brain_id` stays NOT NULL, `rule_revisions` deferral documented (Task 2). The Phase-2 DEFAULT test is correctly flipped, not deleted.
- **Placeholder scan:** none — exact finder signatures, exact line edits, full `V8` SQL.
- **Consistency:** finder names match Spring Data derivation (`findByBrainId`, `findByBrainIdAndActiveTrue`, `countByBrainId`, `countByBrainIdAndActiveTrue`); `TestBrains.DEFAULT_ID` reused; `V8` is the next migration after `V7`.

## Notes — Phase 3 complete after this

After 3c, Phase 3 (co-resident multi-brain correctness) is done: brain-aware reads + writes + per-brain packs + scoped admin reads + no transitional DEFAULT. Remaining milestones: **P4** per-brain model routing + local-LLM stub (per-brain `answer_/utility_` columns already on `brains`; `ModelRouterService` becomes brain-aware) + SSRF allowlist; **P5** per-brain source binding (use the brain's `s3_*`/`local_path` in sync/ingestion); **P6** dashboard Brains screen + security hardening (incl. `?brain=` on `AdminRulesController` reads); **P7** tests + docs. Deferred: `rule_revisions` brain-scoping.
