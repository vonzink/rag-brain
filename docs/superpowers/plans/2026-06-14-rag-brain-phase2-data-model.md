# rag-brain Phase 2 — Data Model (brains table + brain_id) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `brains` registry table and a `brain_id` column to the data + compliance tables (migration `V7`), seed a default brain, and backfill existing rows — with current single-brain behavior **completely unchanged** afterward.

**Architecture:** Co-resident multi-brain via a `brain_id` column (spec `docs/superpowers/specs/2026-06-14-rag-brain-multi-brain-design.md` §6). `V7` creates `brains`, seeds one **well-known default brain** (fixed UUID `00000000-0000-0000-0000-000000000001`), and adds `brain_id UUID NOT NULL DEFAULT '<that uuid>'` to six tables so existing rows backfill and unmodified write paths keep working until Phase 3 wires explicit brain_id. A `DefaultBrainSeeder` (`ApplicationRunner`) reconciles the default brain's descriptive fields to the live env at boot. No existing entity or service code changes (Hibernate `ddl-auto: validate` tolerates the new unmapped columns).

**Tech Stack:** Java 21 · Spring Boot 3.5 · Spring Data JPA · Flyway · PostgreSQL 16 + pgvector · JUnit 5 + Testcontainers · Gradle. **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

---

## Context the engineer needs (verified against the cloned source)

- **Current tables** (after `V3` rename): `brain_documents`, `brain_document_chunks` (data); `ai_conversations`, `ai_messages`, `ai_answer_sources`, `ai_audit_logs` (compliance). All UUID PKs. Migrations run to **V6**; this is **V7**.
- **`ddl-auto: validate`** (`application.yml`): Hibernate validates that *mapped* entity columns exist; **extra DB columns are tolerated**. So adding `brain_id` columns needs **no** change to the existing entities (`MortgageDocument`, `DocumentChunk`, `Conversation`, `Message`, `AnswerSource`, `AuditLog`). Their `brain_id` entity fields are added later (Phase 3) when code uses them.
- **Flyway** runs from `classpath:db/migration` and runs in `@DataJpaTest` (the existing `BrainSettingRepositoryTest` relies on this), so repository tests see the full migrated schema including `V7`.
- **Config bindings:** `brain.slug` (default `mortgage`), `brain.pack` (default `packs/msfg-mortgage`), `brain.corpus.bucket|prefix|region` (S3), `msfg.rag.storage.path` (local), `msfg.rag.routing.default-provider|fallback-provider`, `spring.ai.anthropic.chat.options.model`, `spring.ai.openai.chat.options.model`. All read via `@Value`. There is **no** `@ConfigurationProperties` for `brain.*` and **no** existing `ApplicationRunner`/`CommandLineRunner`.
- **Entity/repo pattern** to mirror: `domain/BrainSetting.java` (plain JPA, `@PrePersist/@PreUpdate` for timestamps) + `repository/BrainSettingRepository.java` (`JpaRepository`).
- **Test pattern** to mirror exactly (`repository/BrainSettingRepositoryTest.java`): `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Testcontainers` with `@Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))`.
- **Out of scope for Phase 2** (noted so you don't add them): `brain_settings` stays global (per spec §8, per-brain models are columns on `brains`); `brain_rule_revisions` is **not** given a `brain_id` here (it becomes brain-aware in Phase 3 when rules/pack become brain-scoped). Existing service/controller code is **not** modified — that is Phase 3+.
- **The fixed default UUID** (`00000000-0000-0000-0000-000000000001`) is a Phase-2 safety bridge: the column `DEFAULT` keeps pre-Phase-3 writers (which don't set `brain_id`) working. Phase 3 drops the `DEFAULT` once all writers pass `brain_id` explicitly.

---

### Task 0: Confirm a green baseline

**Files:** none (verification).

- [ ] **Step 1: Build + test the clone before any change**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass (Testcontainers needs Docker running). If this is not green, stop and report — do not start Phase 2 on a red baseline.

---

### Task 1: `V7` migration + `Brain` entity + `BrainRepository`

**Files:**
- Create: `src/main/resources/db/migration/V7__add_brains_and_brain_id.sql`
- Create: `src/main/java/com/msfg/rag/domain/Brain.java`
- Create: `src/main/java/com/msfg/rag/repository/BrainRepository.java`
- Test:  `src/test/java/com/msfg/rag/repository/BrainRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/msfg/rag/repository/BrainRepositoryTest.java`:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.Brain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainRepositoryTest {

    private static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Autowired
    private TestEntityManager em;

    @Test
    void migrationSeedsExactlyOneDefaultBrain() {
        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals(DEFAULT_ID, def.getId());
        assertEquals("mortgage", def.getSlug());
        assertEquals("packs/msfg-mortgage", def.getPackRef());
        assertTrue(def.isDefault());
        assertTrue(def.isActive());
    }

    @Test
    void atMostOneDefaultBrainIsAllowed() {
        Brain second = new Brain(UUID.randomUUID(), "second", "Second Brain");
        second.setDefault(true);
        assertThrows(DataIntegrityViolationException.class, () -> brains.saveAndFlush(second));
    }

    @Test
    void existingTableInsertWithoutBrainIdDefaultsToDefaultBrain() {
        em.getEntityManager().createNativeQuery(
                "INSERT INTO brain_documents (title, source_name, source_type, file_name) " +
                "VALUES ('T', 'S', 'educational', 'f.pdf')").executeUpdate();
        em.flush();
        Object brainId = em.getEntityManager().createNativeQuery(
                "SELECT brain_id FROM brain_documents WHERE file_name = 'f.pdf'").getSingleResult();
        assertEquals(DEFAULT_ID, brainId);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test --tests "com.msfg.rag.repository.BrainRepositoryTest"
```
Expected: FAIL — compilation error (`Brain`, `BrainRepository`, `findDefaultBrain` do not exist yet).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V7__add_brains_and_brain_id.sql`:

```sql
-- V7: brain registry + per-brain isolation (co-resident multi-brain, spec §6).
-- Creates the brains table, seeds a well-known default brain, and stamps every
-- data + compliance row with brain_id. The column DEFAULT keeps pre-Phase-3
-- write paths (which don't yet set brain_id) working; Phase 3 drops the DEFAULT
-- once all writers pass brain_id explicitly.

CREATE TABLE brains (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug              VARCHAR(100)  NOT NULL UNIQUE,
    display_name      VARCHAR(200)  NOT NULL,
    pack_ref          VARCHAR(500),
    source_type       VARCHAR(20),                      -- s3 | local
    s3_bucket         VARCHAR(255),
    s3_prefix         VARCHAR(500),
    s3_region         VARCHAR(64),
    local_path        VARCHAR(1000),
    answer_provider   VARCHAR(50),
    answer_model      VARCHAR(100),
    utility_provider  VARCHAR(50),
    utility_model     VARCHAR(100),
    local_base_url    VARCHAR(500),
    local_api_key_ref VARCHAR(500),
    is_default        BOOLEAN       NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- At most one default brain.
CREATE UNIQUE INDEX ux_brains_single_default ON brains (is_default) WHERE is_default;

-- Well-known default brain. The fixed id lets the brain_id column DEFAULTs below
-- reference it. Descriptive fields are the app's static defaults; DefaultBrainSeeder
-- reconciles slug/pack/source/model to the live env at boot.
INSERT INTO brains (id, slug, display_name, pack_ref, is_default, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', 'mortgage', 'MSFG Mortgage',
        'packs/msfg-mortgage', TRUE, TRUE);

-- Per-brain isolation on data + compliance tables. NOT NULL + DEFAULT backfills
-- existing rows to the default brain and keeps unmodified inserts working.
ALTER TABLE brain_documents       ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE brain_document_chunks ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_conversations      ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_messages           ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_answer_sources     ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;
ALTER TABLE ai_audit_logs         ADD COLUMN brain_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES brains (id) ON DELETE RESTRICT;

CREATE INDEX idx_brain_documents_brain       ON brain_documents (brain_id);
CREATE INDEX idx_brain_document_chunks_brain ON brain_document_chunks (brain_id);
CREATE INDEX idx_ai_conversations_brain      ON ai_conversations (brain_id);
CREATE INDEX idx_ai_messages_brain           ON ai_messages (brain_id);
CREATE INDEX idx_ai_answer_sources_brain     ON ai_answer_sources (brain_id);
CREATE INDEX idx_ai_audit_logs_brain         ON ai_audit_logs (brain_id);
```

- [ ] **Step 4: Write the `Brain` entity**

Create `src/main/java/com/msfg/rag/domain/Brain.java`:

```java
package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A registered brain: its knowledge source, pack, and model (spec §6). */
@Entity
@Table(name = "brains")
public class Brain {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "pack_ref", length = 500)
    private String packRef;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "s3_bucket", length = 255)
    private String s3Bucket;

    @Column(name = "s3_prefix", length = 500)
    private String s3Prefix;

    @Column(name = "s3_region", length = 64)
    private String s3Region;

    @Column(name = "local_path", length = 1000)
    private String localPath;

    @Column(name = "answer_provider", length = 50)
    private String answerProvider;

    @Column(name = "answer_model", length = 100)
    private String answerModel;

    @Column(name = "utility_provider", length = 50)
    private String utilityProvider;

    @Column(name = "utility_model", length = 100)
    private String utilityModel;

    @Column(name = "local_base_url", length = 500)
    private String localBaseUrl;

    @Column(name = "local_api_key_ref", length = 500)
    private String localApiKeyRef;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Brain() {}

    public Brain(UUID id, String slug, String displayName) {
        this.id = id;
        this.slug = slug;
        this.displayName = displayName;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPackRef() { return packRef; }
    public void setPackRef(String packRef) { this.packRef = packRef; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }
    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }
    public String getS3Region() { return s3Region; }
    public void setS3Region(String s3Region) { this.s3Region = s3Region; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getAnswerProvider() { return answerProvider; }
    public void setAnswerProvider(String answerProvider) { this.answerProvider = answerProvider; }
    public String getAnswerModel() { return answerModel; }
    public void setAnswerModel(String answerModel) { this.answerModel = answerModel; }
    public String getUtilityProvider() { return utilityProvider; }
    public void setUtilityProvider(String utilityProvider) { this.utilityProvider = utilityProvider; }
    public String getUtilityModel() { return utilityModel; }
    public void setUtilityModel(String utilityModel) { this.utilityModel = utilityModel; }
    public String getLocalBaseUrl() { return localBaseUrl; }
    public void setLocalBaseUrl(String localBaseUrl) { this.localBaseUrl = localBaseUrl; }
    public String getLocalApiKeyRef() { return localApiKeyRef; }
    public void setLocalApiKeyRef(String localApiKeyRef) { this.localApiKeyRef = localApiKeyRef; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: Write the `BrainRepository`**

Create `src/main/java/com/msfg/rag/repository/BrainRepository.java`:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.Brain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainRepository extends JpaRepository<Brain, UUID> {

    @Query("select b from Brain b where b.isDefault = true")
    Optional<Brain> findDefaultBrain();

    Optional<Brain> findBySlug(String slug);
}
```

- [ ] **Step 6: Run the test to confirm it passes**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test --tests "com.msfg.rag.repository.BrainRepositoryTest"
```
Expected: PASS (3 tests). If `ddl-auto: validate` complains that an entity mapping mismatches the DB, fix the `Brain` column definitions to match `V7` exactly.

- [ ] **Step 7: Commit**

```bash
cd /Users/zacharyzink/rag-brain && git add src/main/resources/db/migration/V7__add_brains_and_brain_id.sql src/main/java/com/msfg/rag/domain/Brain.java src/main/java/com/msfg/rag/repository/BrainRepository.java src/test/java/com/msfg/rag/repository/BrainRepositoryTest.java && git commit -q -m "$(cat <<'EOF'
Phase 2: brains registry + brain_id isolation (V7)

Add brains table, seed a well-known default brain, and add brain_id
(NOT NULL DEFAULT <default-brain-uuid>) to the six data + compliance
tables so existing rows backfill and unmodified write paths keep working.
Brain entity + BrainRepository. No existing entity/service changes.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `DefaultBrainSeeder` reconciles the default brain from env

**Files:**
- Create: `src/main/java/com/msfg/rag/config/DefaultBrainSeeder.java`
- Test:  `src/test/java/com/msfg/rag/config/DefaultBrainSeederTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/msfg/rag/config/DefaultBrainSeederTest.java`:

```java
package com.msfg.rag.config;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(DefaultBrainSeeder.class)
@TestPropertySource(properties = {
        "brain.slug=mortgage",
        "brain.pack=packs/msfg-mortgage",
        "brain.corpus.bucket=msfg.us",
        "brain.corpus.prefix=rag-brain/",
        "brain.corpus.region=us-west-1",
        "msfg.rag.routing.default-provider=anthropic",
        "spring.ai.anthropic.chat.options.model=claude-haiku-4-5",
        "msfg.rag.routing.fallback-provider=openai",
        "spring.ai.openai.chat.options.model=gpt-4.1-nano"
})
class DefaultBrainSeederTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainRepository brains;

    @Autowired
    private DefaultBrainSeeder seeder;

    @Test
    void reconcilesDefaultBrainFromConfig() {
        seeder.run(null);

        Brain def = brains.findDefaultBrain().orElseThrow();
        assertEquals("mortgage", def.getSlug());
        assertEquals("packs/msfg-mortgage", def.getPackRef());
        assertEquals("s3", def.getSourceType());
        assertEquals("msfg.us", def.getS3Bucket());
        assertEquals("rag-brain/", def.getS3Prefix());
        assertEquals("anthropic", def.getAnswerProvider());
        assertEquals("claude-haiku-4-5", def.getAnswerModel());
        assertEquals("openai", def.getUtilityProvider());
        assertEquals("gpt-4.1-nano", def.getUtilityModel());
    }

    @Test
    void isIdempotent() {
        seeder.run(null);
        seeder.run(null);
        assertEquals(1, brains.count());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test --tests "com.msfg.rag.config.DefaultBrainSeederTest"
```
Expected: FAIL — `DefaultBrainSeeder` does not exist yet.

- [ ] **Step 3: Write the `DefaultBrainSeeder`**

Create `src/main/java/com/msfg/rag/config/DefaultBrainSeeder.java`:

```java
package com.msfg.rag.config;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the default brain (seeded by V7) to this deployment's live config
 * at boot, so its slug/pack/source/model reflect the actual env. Idempotent:
 * it updates the single default brain in place, never creating extra rows.
 */
@Component
public class DefaultBrainSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultBrainSeeder.class);

    private final BrainRepository brains;
    private final String slug;
    private final String packRef;
    private final String s3Bucket;
    private final String s3Prefix;
    private final String s3Region;
    private final String localPath;
    private final String answerProvider;
    private final String answerModel;
    private final String utilityProvider;
    private final String utilityModel;

    public DefaultBrainSeeder(
            BrainRepository brains,
            @Value("${brain.slug:mortgage}") String slug,
            @Value("${brain.pack:packs/msfg-mortgage}") String packRef,
            @Value("${brain.corpus.bucket:}") String s3Bucket,
            @Value("${brain.corpus.prefix:}") String s3Prefix,
            @Value("${brain.corpus.region:}") String s3Region,
            @Value("${msfg.rag.storage.path:}") String localPath,
            @Value("${msfg.rag.routing.default-provider:anthropic}") String answerProvider,
            @Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5}") String answerModel,
            @Value("${msfg.rag.routing.fallback-provider:openai}") String utilityProvider,
            @Value("${spring.ai.openai.chat.options.model:gpt-4.1-nano}") String utilityModel) {
        this.brains = brains;
        this.slug = slug;
        this.packRef = packRef;
        this.s3Bucket = s3Bucket;
        this.s3Prefix = s3Prefix;
        this.s3Region = s3Region;
        this.localPath = localPath;
        this.answerProvider = answerProvider;
        this.answerModel = answerModel;
        this.utilityProvider = utilityProvider;
        this.utilityModel = utilityModel;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Brain brain = brains.findDefaultBrain().orElseThrow(() ->
                new IllegalStateException("No default brain present — V7 migration must seed one"));

        brain.setSlug(slug);
        brain.setPackRef(packRef);
        if (!s3Bucket.isBlank()) {
            brain.setSourceType("s3");
            brain.setS3Bucket(s3Bucket);
            brain.setS3Prefix(s3Prefix);
            brain.setS3Region(s3Region);
        } else if (!localPath.isBlank()) {
            brain.setSourceType("local");
            brain.setLocalPath(localPath);
        }
        brain.setAnswerProvider(answerProvider);
        brain.setAnswerModel(answerModel);
        brain.setUtilityProvider(utilityProvider);
        brain.setUtilityModel(utilityModel);
        brains.save(brain);

        log.info("Default brain reconciled from config: slug='{}', pack='{}', source={}",
                brain.getSlug(), brain.getPackRef(), brain.getSourceType());
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test --tests "com.msfg.rag.config.DefaultBrainSeederTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
cd /Users/zacharyzink/rag-brain && git add src/main/java/com/msfg/rag/config/DefaultBrainSeeder.java src/test/java/com/msfg/rag/config/DefaultBrainSeederTest.java && git commit -q -m "$(cat <<'EOF'
Phase 2: DefaultBrainSeeder reconciles default brain from env

Boot-time ApplicationRunner updates the V7-seeded default brain's
slug/pack/source/model to the live deployment config. Idempotent.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Full regression + boot verification

**Files:** none (verification).

- [ ] **Step 1: Run the entire suite**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass — including the existing `MsfgGoldenPackTest`, `AnswerValidationServiceTest`, and every repository test. A `ddl-auto: validate` failure here means an entity/DB mismatch from `V7` — fix the migration or `Brain` mapping, never delete a passing test.

- [ ] **Step 2: Boot against a fresh DB and confirm migration + seeder run clean**

Run (use a throwaway DB to prove `V7` applies from scratch; brings the stack up, boots on 8090, then tears down):
```bash
cd /Users/zacharyzink/rag-brain && docker compose up -d && \
set -a && source .env && set +a && \
./gradlew bootRun --args='--server.port=8090'
```
Expected logs: Flyway applies `V7__add_brains_and_brain_id`; `DefaultBrainSeeder` logs `Default brain reconciled from config: slug='mortgage' …`; `Started MsfgRagApplication`; no stack traces. (Run `bootRun` in the background, confirm the log lines, then stop it and `docker compose down`.)

- [ ] **Step 3: Phase 2 completion gate**

  - [ ] `V7` creates `brains` + a single default brain + `brain_id` on all six tables; `./gradlew test` green.
  - [ ] Existing single-brain behavior unchanged (golden-pack + compliance tests pass; a `brain_documents` insert with no `brain_id` still works via the column DEFAULT).
  - [ ] App boots; Flyway `V7` + `DefaultBrainSeeder` run cleanly.
  - [ ] Two commits landed; `msfg-rag` untouched (`git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only its pre-existing `?? scripts/`).

---

## Self-Review

- **Spec coverage (§6, §14 Phase 2):** `brains` table (Task 1), `brain_id` on all six data+compliance tables with `NOT NULL` via DEFAULT (Task 1), default-brain seed + backfill (Task 1 migration), `DefaultBrainSeeder` reconciliation (Task 2), `is_default` partial-unique index (Task 1), behavior-intact verification (Task 3). `brain_settings`/`brain_rule_revisions` correctly excluded per spec (noted in Context).
- **Placeholder scan:** none — full SQL, entity, repo, seeder, and test code provided; the only constant is the documented fixed default-brain UUID.
- **Type/name consistency:** `findDefaultBrain()` (JPQL on field `isDefault`), `setDefault(boolean)`/`isDefault()`, column names match `V7` exactly (`brain_id`, `is_default`, `pack_ref`, `s3_*`, `local_*`, `answer_*`, `utility_*`), default UUID `00000000-0000-0000-0000-000000000001` identical across migration, entity test, and repo test.

## Notes for Phase 3

Phase 3 adds the `brain_id` entity fields + the request-scoped brain context (carried explicitly into `AuditLogService.record`), the `DomainPackRegistry`, the `brain_id` predicate on `DocumentChunkRepository` queries, and ingestion/persistence writes setting `brain_id` — then **drops the column DEFAULT** added here so all writers must pass `brain_id` explicitly. `brain_rule_revisions` gains `brain_id` when rules become brain-aware.
