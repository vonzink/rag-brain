# rag-brain Phase 5 — Per-Brain Source Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make each brain sync from **its own** knowledge source — a local folder (`local_path`) or its own S3 bucket/prefix (`s3_bucket`/`s3_prefix`/`s3_region`) — instead of the single global `brain.corpus.*` S3 source. This is the engine behind "connect a folder."

**Architecture:** Add a `LocalFolderCorpusSource` (reads a brain's folder; supported files; optional `_manifest.json`, else metadata defaults) and a `CorpusSourceFactory` that returns the right `CorpusSource` for a `Brain` (S3 or local, built from the brain's columns). `SyncService.sync(dryRun, brainId)` (already brain-scoped from Phase 3) loads the brain and uses the factory instead of the injected global `CorpusSource`. `S3CorpusSource` becomes a plain class the factory instantiates per brain (it already has a `(bucket, prefix, region)` constructor). The default brain's seeded S3 columns (`msfg.us`/`rag-brain/`/`us-west-1`) reproduce today's sync exactly. **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Tech Stack:** Java 21 · Spring Boot · AWS SDK v2 · NIO · JUnit 5 + Mockito + Testcontainers.

---

## Context (verified)

- `CorpusSource` (interface): `List<String> listFiles()`, `byte[] fetch(String fileName)`, `Optional<byte[]> fetchManifest()`.
- `S3CorpusSource` (`@Component`): built from global `@Value("${brain.corpus.bucket/prefix/region}")`; `listFiles` lists keys under the prefix (drops the prefix, `_manifest.json`, folder markers); `fetch` gets bytes; `fetchManifest` returns the `_manifest.json` or empty. **Only consumer is `SyncService`** (confirm via grep). It already has a usable `(bucket, prefix, region)` constructor body — only the `@Component`/`@Value` need removing so the factory can build it per brain.
- `SyncManifest.parse(Optional<byte[]>)` tolerates a missing manifest → defaults; `resolve(fileName)` derives the title from the filename and uses default `sourceName`/`sourceType` when absent. So a manifest-less local folder ingests cleanly with sensible defaults.
- `SyncService.sync(boolean dryRun, UUID brainId)` (Phase 3): loads `documentRepository.findByBrainId(brainId)` (already brain-scoped), plans, and ingests via `DocumentIngestionService.ingest(..., brainId)`. It injects the single global `CorpusSource corpusSource` and uses it at lines ~44 (`fetchManifest`), ~45 (`listFiles`), and in `ingest` (`fetch`). These become `corpusSourceFactory.forBrain(brain)`.
- `Brain` getters exist: `getSourceType()`, `getS3Bucket()`, `getS3Prefix()`, `getS3Region()`, `getLocalPath()`, `getSlug()`. `BrainRepository.findById(UUID)`.
- `DocumentAdminController.sync(dryRun, @RequestParam brain)` already resolves the brain and calls `syncService.sync(dryRun, brainId)` (Phase 3c) — no controller change needed.
- The global `brain.corpus.*` env stays (it seeds the **default brain's** S3 columns via `DefaultBrainSeeder`); only the global `S3CorpusSource` bean goes away.

---

### Task 0: Green baseline
- [ ] `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL`. Red → stop. Also: `grep -rn "CorpusSource" src/main` to confirm `SyncService` is the only injector of `CorpusSource`/`S3CorpusSource` (report if not).

---

### Task 1: `LocalFolderCorpusSource`

**Files:**
- Create: `src/main/java/com/msfg/rag/service/sync/LocalFolderCorpusSource.java`
- Test: `src/test/java/com/msfg/rag/service/sync/LocalFolderCorpusSourceTest.java`

- [ ] **Step 1: Write the failing test**

`LocalFolderCorpusSourceTest.java` — use a JUnit `@TempDir`; write a `.pdf`, a `.txt`, an unsupported `.png`, and a `_manifest.json`; assert `listFiles()` returns only the supported files (sorted, excluding the manifest and the png), `fetch` returns the bytes, `fetchManifest` returns the manifest bytes, and a folder with no manifest → `fetchManifest()` empty.
```java
package com.msfg.rag.service.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalFolderCorpusSourceTest {

    @Test
    void listsSupportedFilesAndReadsBytes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("guide.txt"), "hello");
        Files.write(dir.resolve("doc.pdf"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("image.png"), new byte[]{9});
        Files.writeString(dir.resolve("_manifest.json"), "{\"defaults\":{}}");

        LocalFolderCorpusSource src = new LocalFolderCorpusSource(dir.toString());

        assertEquals(List.of("doc.pdf", "guide.txt"), src.listFiles());     // sorted, no png, no manifest
        assertArrayEquals(new byte[]{1, 2, 3}, src.fetch("doc.pdf"));
        assertTrue(src.fetchManifest().isPresent());
    }

    @Test
    void missingManifestIsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.md"), "x");
        assertTrue(new LocalFolderCorpusSource(dir.toString()).fetchManifest().isEmpty());
        assertEquals(List.of("a.md"), new LocalFolderCorpusSource(dir.toString()).listFiles());
    }
}
```

- [ ] **Step 2: Run → FAIL**: `./gradlew test --tests "com.msfg.rag.service.sync.LocalFolderCorpusSourceTest"`.

- [ ] **Step 3: Implement `LocalFolderCorpusSource`**
```java
package com.msfg.rag.service.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Corpus from a local folder (a brain's local_path). Top-level supported files
 * only; an optional _manifest.json in the folder supplies metadata, otherwise
 * SyncManifest defaults apply (title derived from the filename).
 */
public class LocalFolderCorpusSource implements CorpusSource {

    static final String MANIFEST_NAME = "_manifest.json";
    private static final Set<String> SUPPORTED =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private final Path root;

    public LocalFolderCorpusSource(String localPath) {
        this.root = Path.of(localPath).toAbsolutePath().normalize();
    }

    @Override
    public List<String> listFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(MANIFEST_NAME))
                    .filter(LocalFolderCorpusSource::isSupported)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Listing corpus folder " + root, e);
        }
    }

    @Override
    public byte[] fetch(String fileName) {
        try {
            return Files.readAllBytes(root.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("Reading corpus file " + fileName, e);
        }
    }

    @Override
    public Optional<byte[]> fetchManifest() {
        Path manifest = root.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(manifest));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean isSupported(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.US));
    }
}
```

- [ ] **Step 4: Run → PASS**.

- [ ] **Step 5: Commit** `git add -A && git commit -q -m "Phase 5: LocalFolderCorpusSource (local-folder corpus)\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"` (use a heredoc for the multi-line message).

---

### Task 2: `CorpusSourceFactory` + per-brain `SyncService`

**Files:**
- Create: `src/main/java/com/msfg/rag/service/sync/CorpusSourceFactory.java`
- Modify: `src/main/java/com/msfg/rag/service/sync/S3CorpusSource.java` (remove `@Component`/`@Value`)
- Modify: `src/main/java/com/msfg/rag/service/sync/SyncService.java` (inject the factory + `BrainRepository`)
- Modify test: `src/test/java/com/msfg/rag/service/sync/SyncServiceTest.java`
- Test: `src/test/java/com/msfg/rag/service/sync/CorpusSourceFactoryTest.java`

- [ ] **Step 1: Write the failing factory test**

`CorpusSourceFactoryTest.java`: a brain with `sourceType="local"` + `localPath` → `forBrain` returns a `LocalFolderCorpusSource`; `sourceType="s3"` + bucket → returns an `S3CorpusSource`; a local brain with blank `localPath` (or s3 brain with blank bucket, or unknown type) → `IllegalStateException`.
```java
// build Brain via new Brain(UUID, slug, name) + setSourceType/setLocalPath/setS3Bucket/...
assertInstanceOf(LocalFolderCorpusSource.class, factory.forBrain(localBrain));
assertInstanceOf(S3CorpusSource.class, factory.forBrain(s3Brain));
assertThrows(IllegalStateException.class, () -> factory.forBrain(brainWithNoSource));
```
(Building an `S3CorpusSource` constructs an `S3Client` for the region but makes no network call, so the assertion is safe offline.)

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement the factory**
```java
package com.msfg.rag.service.sync;

import com.msfg.rag.domain.Brain;
import org.springframework.stereotype.Component;

/** Builds the CorpusSource a brain syncs from, by its source_type + binding columns. */
@Component
public class CorpusSourceFactory {

    public CorpusSource forBrain(Brain brain) {
        String type = brain.getSourceType();
        if ("local".equalsIgnoreCase(type)) {
            if (isBlank(brain.getLocalPath())) {
                throw new IllegalStateException("Brain '" + brain.getSlug() + "' is local but has no local_path");
            }
            return new LocalFolderCorpusSource(brain.getLocalPath());
        }
        if ("s3".equalsIgnoreCase(type)) {
            if (isBlank(brain.getS3Bucket())) {
                throw new IllegalStateException("Brain '" + brain.getSlug() + "' is s3 but has no s3_bucket");
            }
            return new S3CorpusSource(brain.getS3Bucket(), brain.getS3Prefix(), brain.getS3Region());
        }
        throw new IllegalStateException(
                "Brain '" + brain.getSlug() + "' has no/unknown source_type: " + type);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 4: Make `S3CorpusSource` a plain class**

Remove the `@Component` annotation and the `@Value` annotations from the constructor (and the now-unused `org.springframework.beans.factory.annotation.Value` / `org.springframework.stereotype.Component` imports). The constructor stays `public S3CorpusSource(String bucket, String prefix, String region)` with its existing body. Everything else unchanged.

- [ ] **Step 5: Make `SyncService` build the source per brain**

Replace the injected `CorpusSource corpusSource` with `CorpusSourceFactory corpusSourceFactory` and add `BrainRepository brainRepository` to the constructor. In `sync(boolean dryRun, UUID brainId)`, at the top resolve the source from the brain:
```java
Brain brain = brainRepository.findById(brainId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
CorpusSource corpusSource = corpusSourceFactory.forBrain(brain);
```
Then use that local `corpusSource` variable for `fetchManifest()`, `listFiles()`, and thread it into `execute(...)` / `ingest(...)` (replace the field references with the local variable — pass `corpusSource` as a parameter to `execute`/`ingest`, or resolve once and use throughout `sync`). The `documentRepository.findByBrainId(brainId)` read (Phase 3c) is unchanged. (imports: `com.msfg.rag.domain.Brain`, `com.msfg.rag.repository.BrainRepository`.)

- [ ] **Step 6: Fix `SyncServiceTest`**

It mocks `CorpusSource` and injects it. Now mock `CorpusSourceFactory` (`when(corpusSourceFactory.forBrain(any())).thenReturn(corpusSource)` where `corpusSource` is the existing mock) and `BrainRepository` (`when(brainRepository.findById(any())).thenReturn(Optional.of(brain))`). The `sync(dryRun, brainId)` calls already pass a brain id. Keep every existing assertion.

- [ ] **Step 7: Run → PASS** (`CorpusSourceFactoryTest`, `SyncServiceTest`), then full `./gradlew test`.

- [ ] **Step 8: Commit** (heredoc message):
```
Phase 5: per-brain corpus source (S3 or local folder)

CorpusSourceFactory builds each brain's CorpusSource from its source_type +
binding columns; SyncService.sync resolves the brain's source instead of a
single global S3 bean. S3CorpusSource is now a plain class the factory builds
per brain. Default brain (seeded S3 columns) syncs exactly as before.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task 3: Regression + verify a local-folder brain

**Files:**
- Test: `src/test/java/com/msfg/rag/service/sync/SyncServiceLocalFolderIntegrationTest.java` (Testcontainers) — optional but recommended.

- [ ] **Step 1:** Full `./gradlew test` → green (incl. `S3CorpusSourceTest` static `filterKeys` still passes; golden/compliance unchanged).
- [ ] **Step 2 (recommended): integration test** — `@DataJpaTest`+Testcontainers (or `@SpringBootTest`): create a brain with `sourceType="local"` + a `@TempDir` `localPath` containing a `.txt`; run the real `SyncService.sync(false, brainId)` (real `DocumentIngestionService` — note it embeds via the embedding provider, so either stub the embedding or assert at the planning/`dryRun=true` level to avoid a live API call). Minimum: assert `sync(true, brainId)` (dry run) plans an `UPLOAD` for the folder's file. Confirms the brain's folder is the source.
- [ ] **Step 3:** Boot on 8090: app starts (no global `S3CorpusSource` bean now); default-brain `POST /api/ai/documents/sync?dryRun=true` still returns a plan against the default brain's S3 source. Stop + `docker compose down`.
- [ ] **Step 4: Gate:** local-folder source works; default S3 brain syncs unchanged; full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/` (+ your RUNBOOK edit).

---

## Self-Review

- **Spec coverage (§6/§9 per-brain source):** `LocalFolderCorpusSource` (Task 1) + `CorpusSourceFactory` (Task 2) + `SyncService` resolves the brain's source (Task 2). Default brain behavior preserved (seeded S3 columns).
- **Placeholder scan:** none — full new-class code, exact edits, test code.
- **Consistency:** `CorpusSource` interface unchanged; factory keyed on `source_type` (`local`/`s3`); `SyncService.sync(dryRun, brainId)` signature unchanged (controller untouched).
- **Risk:** removing `@Component` from `S3CorpusSource` is safe iff `SyncService` is its only injector (Task 0 grep confirms). Building an `S3Client` per sync is acceptable (sync is infrequent).

## Notes — P6 (next)

P6 makes this usable from the UI: brain CRUD admin API (list/create/update/activate/delete + per-brain sync) under `/api/ai/admin`; a **Brains** dashboard screen (create a brain → pick pack + source `local`/`s3` + the folder/bucket + model → Sync now → set active); a brain selector on the existing screens; YAML export/import; and the security hardening (admin 401 tests, per-brain secret handling, `?brain=` on AdminRules reads, plus P4b-2's per-brain local endpoint + SSRF). "Connect a folder from the dashboard" = create a `local` brain with a `local_path` + Sync, all from the Brains screen.
