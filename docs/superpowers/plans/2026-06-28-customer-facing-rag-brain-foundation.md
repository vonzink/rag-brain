# Customer-Facing RAG Brain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first customer-facing foundation for `rag-brain`: brain profiles, public ask contract, public-token/domain validation, clarification routing, source visibility filtering, trace expansion, and admin/dashboard controls.

**Architecture:** Extend the existing Spring Boot + React + PostgreSQL/pgvector app instead of replacing it. Public website calls go through a new public-safe endpoint and response contract; admin controls remain under `/api/ai/admin`. Retrieval visibility is enforced in SQL before chunks reach prompt assembly.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, PostgreSQL 16, pgvector, JUnit 5, Mockito, Testcontainers, React, Vite, TypeScript.

## Global Constraints

- Work only in `/Users/zacharyzink/rag-brain`; do not modify `/Users/zacharyzink/MSFG/msfg-rag`.
- Do not hard-code mortgage/MSFG behavior into the generic platform.
- Public website assistants must not expose admin capability.
- Public ask token is separate from `ADMIN_API_KEY`.
- Public API cannot ingest, edit settings, view traces, or list documents.
- Public requests retrieve only `PUBLIC` source versions/chunks.
- Source visibility must be enforced before model prompt assembly.
- Public response types are `ANSWER`, `CLARIFY`, `NAVIGATE`, and `ESCALATE`.
- V1 URL crawler work is not part of this foundation plan; this plan creates the source/visibility/profile foundation that URL learning will use.
- Preserve existing document ingestion, hierarchical chunking, pgvector/HNSW retrieval, dashboard workflows, and admin API behavior.
- Use migrations after existing `V12__generic_default_brain.sql`; the next migration in this branch is `V13__customer_facing_foundation.sql`.

---

## File Structure

### Backend database

- Create `src/main/resources/db/migration/V13__customer_facing_foundation.sql`
  - Adds `brain_profiles`
  - Adds `clarification_rules`
  - Adds source visibility/trust columns to `brain_documents`
  - Adds trace fields to `rag_traces`

### Backend domain and repositories

- Create `src/main/java/com/msfg/rag/domain/BrainMode.java`
- Create `src/main/java/com/msfg/rag/domain/ResponseType.java`
- Create `src/main/java/com/msfg/rag/domain/SourceVisibility.java`
- Create `src/main/java/com/msfg/rag/domain/SourceTrustLevel.java`
- Create `src/main/java/com/msfg/rag/domain/BrainProfile.java`
- Create `src/main/java/com/msfg/rag/domain/ClarificationRule.java`
- Create `src/main/java/com/msfg/rag/repository/BrainProfileRepository.java`
- Create `src/main/java/com/msfg/rag/repository/ClarificationRuleRepository.java`
- Modify `src/main/java/com/msfg/rag/domain/MortgageDocument.java`
- Modify `src/main/java/com/msfg/rag/domain/RagTrace.java`

### Backend DTOs and controllers

- Create `src/main/java/com/msfg/rag/dto/BrainProfileDto.java`
- Create `src/main/java/com/msfg/rag/dto/BrainProfileRequest.java`
- Create `src/main/java/com/msfg/rag/dto/PublicAskRequest.java`
- Create `src/main/java/com/msfg/rag/dto/PublicAskResponse.java`
- Create `src/main/java/com/msfg/rag/dto/PublicRecommendedPageDto.java`
- Create `src/main/java/com/msfg/rag/dto/ClarificationQuestionDto.java`
- Create `src/main/java/com/msfg/rag/controller/AdminBrainProfileController.java`
- Create `src/main/java/com/msfg/rag/controller/PublicAskController.java`

### Backend services

- Create `src/main/java/com/msfg/rag/service/profile/BrainProfileService.java`
- Create `src/main/java/com/msfg/rag/service/publicapi/PublicAccessService.java`
- Create `src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java`
- Create `src/main/java/com/msfg/rag/service/clarification/ClarificationDecision.java`
- Create `src/main/java/com/msfg/rag/service/clarification/ClarificationService.java`
- Modify `src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java`
- Modify `src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java`
- Modify `src/main/java/com/msfg/rag/service/AskService.java`
- Modify `src/main/java/com/msfg/rag/service/audit/RagTraceService.java`

### Dashboard

- Modify `dashboard/src/types.ts`
- Modify `dashboard/src/api.ts`
- Modify `dashboard/src/App.tsx`
- Create `dashboard/src/screens/Personality.tsx`
- Modify `dashboard/src/screens/TestConsole.tsx`

### Tests

- Create `src/test/java/com/msfg/rag/repository/BrainProfileRepositoryTest.java`
- Create `src/test/java/com/msfg/rag/controller/AdminBrainProfileControllerTest.java`
- Create `src/test/java/com/msfg/rag/service/publicapi/PublicAccessServiceTest.java`
- Create `src/test/java/com/msfg/rag/service/clarification/ClarificationServiceTest.java`
- Create `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`
- Modify `src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java`
- Modify `src/test/java/com/msfg/rag/repository/HybridSearchIntegrationTest.java`
- Modify `src/test/java/com/msfg/rag/service/AskServiceTest.java`

---

### Task 1: Schema, Domain Types, And Repository Baseline

**Files:**
- Create: `src/main/resources/db/migration/V13__customer_facing_foundation.sql`
- Create: `src/main/java/com/msfg/rag/domain/BrainMode.java`
- Create: `src/main/java/com/msfg/rag/domain/ResponseType.java`
- Create: `src/main/java/com/msfg/rag/domain/SourceVisibility.java`
- Create: `src/main/java/com/msfg/rag/domain/SourceTrustLevel.java`
- Create: `src/main/java/com/msfg/rag/domain/BrainProfile.java`
- Create: `src/main/java/com/msfg/rag/domain/ClarificationRule.java`
- Create: `src/main/java/com/msfg/rag/repository/BrainProfileRepository.java`
- Create: `src/main/java/com/msfg/rag/repository/ClarificationRuleRepository.java`
- Modify: `src/main/java/com/msfg/rag/domain/MortgageDocument.java`
- Modify: `src/main/java/com/msfg/rag/domain/RagTrace.java`
- Test: `src/test/java/com/msfg/rag/repository/BrainProfileRepositoryTest.java`

**Interfaces:**
- Produces: `BrainProfileRepository.findByBrainId(UUID brainId): Optional<BrainProfile>`
- Produces: `ClarificationRuleRepository.findByBrainIdAndActiveTrueOrderByPriorityAsc(UUID brainId): List<ClarificationRule>`
- Produces: `MortgageDocument.getVisibility(): SourceVisibility`
- Produces: `MortgageDocument.getTrustLevel(): SourceTrustLevel`
- Produces: new trace setters on `RagTrace` for response type, missing facts, collected facts, visibility filter, confidence reason, and validation outcome.

- [ ] **Step 1: Write the failing repository/migration test**

Create `src/test/java/com/msfg/rag/repository/BrainProfileRepositoryTest.java`:

```java
package com.msfg.rag.repository;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.domain.ClarificationRule;
import com.msfg.rag.domain.SourceTrustLevel;
import com.msfg.rag.domain.SourceVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainProfileRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    BrainProfileRepository profiles;

    @Autowired
    ClarificationRuleRepository rules;

    @Autowired
    MortgageDocumentRepository documents;

    @Test
    void defaultProfileIsSeededForDefaultBrain() {
        BrainProfile profile = profiles.findByBrainId(TestBrains.DEFAULT_ID).orElseThrow();
        assertEquals(BrainMode.PUBLIC_SITE, profile.getMode());
        assertEquals(0.90, profile.getConfidenceTarget());
        assertTrue(profile.isPublicEnabled());
    }

    @Test
    void clarificationRulesAreReturnedByPriority() {
        ClarificationRule late = new ClarificationRule();
        late.setBrainId(TestBrains.DEFAULT_ID);
        late.setTopic("eligibility");
        late.setIntent("ELIGIBILITY");
        late.setRequiredFacts(List.of("occupancy"));
        late.setQuestion("Is this for a primary residence?");
        late.setPriority(20);
        late.setRequiredForPublic(true);
        late.setOptionalForGeneral(false);

        ClarificationRule early = new ClarificationRule();
        early.setBrainId(TestBrains.DEFAULT_ID);
        early.setTopic("eligibility");
        early.setIntent("ELIGIBILITY");
        early.setRequiredFacts(List.of("loanPurpose"));
        early.setQuestion("Is this purchase, refinance, or construction?");
        early.setPriority(10);
        early.setRequiredForPublic(true);
        early.setOptionalForGeneral(false);

        rules.save(late);
        rules.save(early);

        List<ClarificationRule> ordered =
                rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID);
        assertEquals("loanPurpose", ordered.get(0).getRequiredFacts().get(0));
        assertEquals("occupancy", ordered.get(1).getRequiredFacts().get(0));
    }

    @Test
    void documentDefaultsArePublicApproved() {
        var doc = documents.findByBrainId(TestBrains.DEFAULT_ID).stream().findFirst();
        assertTrue(doc.isEmpty() || doc.get().getVisibility() == SourceVisibility.PUBLIC);
        assertTrue(doc.isEmpty() || doc.get().getTrustLevel() == SourceTrustLevel.APPROVED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.BrainProfileRepositoryTest
```

Expected: FAIL because `BrainProfile`, repositories, and the V13 schema do not exist.

- [ ] **Step 3: Add V13 migration**

Create `src/main/resources/db/migration/V13__customer_facing_foundation.sql`:

```sql
CREATE TABLE brain_profiles (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id              UUID NOT NULL UNIQUE REFERENCES brains (id) ON DELETE RESTRICT,
    mode                  VARCHAR(40) NOT NULL DEFAULT 'PUBLIC_SITE',
    purpose               TEXT NOT NULL DEFAULT 'Answer questions from approved sources.',
    audience              VARCHAR(120) NOT NULL DEFAULT 'public visitor',
    personality           TEXT NOT NULL DEFAULT 'Conversational, concise, source-grounded assistant.',
    tone                  VARCHAR(80) NOT NULL DEFAULT 'professional',
    expertise_level       VARCHAR(80) NOT NULL DEFAULT 'intermediate',
    answer_length         VARCHAR(40) NOT NULL DEFAULT 'balanced',
    confidence_target     DOUBLE PRECISION NOT NULL DEFAULT 0.90,
    clarification_policy  TEXT NOT NULL DEFAULT 'Ask one focused clarifying question when required facts are missing.',
    escalation_policy     TEXT NOT NULL DEFAULT 'Escalate personalized, unsupported, sensitive, or low-confidence requests.',
    citation_policy       VARCHAR(80) NOT NULL DEFAULT 'required_when_sources_used',
    cta_policy            TEXT NOT NULL DEFAULT 'Recommend relevant pages or a human handoff when useful.',
    disclaimer            TEXT NOT NULL DEFAULT 'This answer is generated from approved source context and may be incomplete.',
    public_enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    public_token_hash     VARCHAR(128),
    allowed_domains       JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clarification_rules (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_id              UUID NOT NULL REFERENCES brains (id) ON DELETE RESTRICT,
    topic                 VARCHAR(120) NOT NULL,
    intent                VARCHAR(80) NOT NULL,
    required_facts        JSONB NOT NULL DEFAULT '[]'::jsonb,
    question              TEXT NOT NULL,
    priority              INTEGER NOT NULL DEFAULT 100,
    required_for_public   BOOLEAN NOT NULL DEFAULT TRUE,
    optional_for_general  BOOLEAN NOT NULL DEFAULT FALSE,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE brain_documents
    ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN trust_level VARCHAR(20) NOT NULL DEFAULT 'APPROVED';

ALTER TABLE rag_traces
    ADD COLUMN response_type VARCHAR(40),
    ADD COLUMN clarification_decision JSONB,
    ADD COLUMN missing_facts JSONB,
    ADD COLUMN collected_facts JSONB,
    ADD COLUMN visibility_filter VARCHAR(20),
    ADD COLUMN confidence_reason JSONB,
    ADD COLUMN validation_outcome VARCHAR(120);

INSERT INTO brain_profiles (brain_id, mode)
SELECT id, 'PUBLIC_SITE'
FROM brains
ON CONFLICT (brain_id) DO NOTHING;

CREATE INDEX idx_brain_profiles_brain ON brain_profiles (brain_id);
CREATE INDEX idx_clarification_rules_brain_priority
    ON clarification_rules (brain_id, active, priority);
CREATE INDEX idx_brain_documents_visibility
    ON brain_documents (brain_id, visibility, trust_level);
```

- [ ] **Step 4: Add enums**

Create the four enum files:

```java
package com.msfg.rag.domain;

public enum BrainMode {
    PUBLIC_SITE,
    PRIVATE_SITE,
    SECURE_DEPLOYMENT
}
```

```java
package com.msfg.rag.domain;

public enum ResponseType {
    ANSWER,
    CLARIFY,
    NAVIGATE,
    ESCALATE
}
```

```java
package com.msfg.rag.domain;

public enum SourceVisibility {
    PUBLIC,
    INTERNAL,
    SECURE
}
```

```java
package com.msfg.rag.domain;

public enum SourceTrustLevel {
    AUTHORITATIVE,
    APPROVED,
    REFERENCE,
    EXPERIMENTAL,
    BLOCKED
}
```

- [ ] **Step 5: Add `BrainProfile` entity**

Create `src/main/java/com/msfg/rag/domain/BrainProfile.java` with fields matching the migration. Use `@Enumerated(EnumType.STRING)` for `mode`, `@JdbcTypeCode(SqlTypes.JSON)` for `allowedDomains`, and `@PrePersist`/`@PreUpdate` timestamping matching `Brain.java`.

Required public methods:

```java
public UUID getId();
public UUID getBrainId();
public void setBrainId(UUID brainId);
public BrainMode getMode();
public void setMode(BrainMode mode);
public String getPurpose();
public void setPurpose(String purpose);
public String getAudience();
public void setAudience(String audience);
public String getPersonality();
public void setPersonality(String personality);
public String getTone();
public void setTone(String tone);
public String getExpertiseLevel();
public void setExpertiseLevel(String expertiseLevel);
public String getAnswerLength();
public void setAnswerLength(String answerLength);
public double getConfidenceTarget();
public void setConfidenceTarget(double confidenceTarget);
public String getClarificationPolicy();
public void setClarificationPolicy(String clarificationPolicy);
public String getEscalationPolicy();
public void setEscalationPolicy(String escalationPolicy);
public String getCitationPolicy();
public void setCitationPolicy(String citationPolicy);
public String getCtaPolicy();
public void setCtaPolicy(String ctaPolicy);
public String getDisclaimer();
public void setDisclaimer(String disclaimer);
public boolean isPublicEnabled();
public void setPublicEnabled(boolean publicEnabled);
public String getPublicTokenHash();
public void setPublicTokenHash(String publicTokenHash);
public List<String> getAllowedDomains();
public void setAllowedDomains(List<String> allowedDomains);
public OffsetDateTime getCreatedAt();
public OffsetDateTime getUpdatedAt();
```

- [ ] **Step 6: Add `ClarificationRule` entity**

Create `src/main/java/com/msfg/rag/domain/ClarificationRule.java` with `@JdbcTypeCode(SqlTypes.JSON)` for `requiredFacts`.

Required public methods:

```java
public UUID getId();
public UUID getBrainId();
public void setBrainId(UUID brainId);
public String getTopic();
public void setTopic(String topic);
public String getIntent();
public void setIntent(String intent);
public List<String> getRequiredFacts();
public void setRequiredFacts(List<String> requiredFacts);
public String getQuestion();
public void setQuestion(String question);
public int getPriority();
public void setPriority(int priority);
public boolean isRequiredForPublic();
public void setRequiredForPublic(boolean requiredForPublic);
public boolean isOptionalForGeneral();
public void setOptionalForGeneral(boolean optionalForGeneral);
public boolean isActive();
public void setActive(boolean active);
public OffsetDateTime getCreatedAt();
public OffsetDateTime getUpdatedAt();
```

- [ ] **Step 7: Add repositories**

Create `BrainProfileRepository`:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrainProfileRepository extends JpaRepository<BrainProfile, UUID> {
    Optional<BrainProfile> findByBrainId(UUID brainId);
}
```

Create `ClarificationRuleRepository`:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.ClarificationRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClarificationRuleRepository extends JpaRepository<ClarificationRule, UUID> {
    List<ClarificationRule> findByBrainIdAndActiveTrueOrderByPriorityAsc(UUID brainId);
}
```

- [ ] **Step 8: Extend `MortgageDocument`**

Add enum fields and getters/setters:

```java
@Enumerated(EnumType.STRING)
@Column(name = "visibility", nullable = false, length = 20)
private SourceVisibility visibility = SourceVisibility.PUBLIC;

@Enumerated(EnumType.STRING)
@Column(name = "trust_level", nullable = false, length = 20)
private SourceTrustLevel trustLevel = SourceTrustLevel.APPROVED;

public SourceVisibility getVisibility() { return visibility; }
public void setVisibility(SourceVisibility visibility) { this.visibility = visibility; }
public SourceTrustLevel getTrustLevel() { return trustLevel; }
public void setTrustLevel(SourceTrustLevel trustLevel) { this.trustLevel = trustLevel; }
```

- [ ] **Step 9: Extend `RagTrace`**

Add fields and setters/getters:

```java
@Column(name = "response_type", length = 40)
private String responseType;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "clarification_decision", columnDefinition = "jsonb")
private Map<String, Object> clarificationDecision;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "missing_facts", columnDefinition = "jsonb")
private List<String> missingFacts;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "collected_facts", columnDefinition = "jsonb")
private Map<String, Object> collectedFacts;

@Column(name = "visibility_filter", length = 20)
private String visibilityFilter;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "confidence_reason", columnDefinition = "jsonb")
private Map<String, Object> confidenceReason;

@Column(name = "validation_outcome", length = 120)
private String validationOutcome;
```

- [ ] **Step 10: Run repository test**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.BrainProfileRepositoryTest
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/db/migration/V13__customer_facing_foundation.sql \
  src/main/java/com/msfg/rag/domain/BrainMode.java \
  src/main/java/com/msfg/rag/domain/ResponseType.java \
  src/main/java/com/msfg/rag/domain/SourceVisibility.java \
  src/main/java/com/msfg/rag/domain/SourceTrustLevel.java \
  src/main/java/com/msfg/rag/domain/BrainProfile.java \
  src/main/java/com/msfg/rag/domain/ClarificationRule.java \
  src/main/java/com/msfg/rag/domain/MortgageDocument.java \
  src/main/java/com/msfg/rag/domain/RagTrace.java \
  src/main/java/com/msfg/rag/repository/BrainProfileRepository.java \
  src/main/java/com/msfg/rag/repository/ClarificationRuleRepository.java \
  src/test/java/com/msfg/rag/repository/BrainProfileRepositoryTest.java
git commit -m "Add customer-facing brain profile schema"
```

---

### Task 2: Brain Profile Service And Admin API

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/BrainProfileDto.java`
- Create: `src/main/java/com/msfg/rag/dto/BrainProfileRequest.java`
- Create: `src/main/java/com/msfg/rag/service/profile/BrainProfileService.java`
- Create: `src/main/java/com/msfg/rag/controller/AdminBrainProfileController.java`
- Test: `src/test/java/com/msfg/rag/controller/AdminBrainProfileControllerTest.java`
- Modify: `src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java`

**Interfaces:**
- Consumes: `BrainProfileRepository.findByBrainId(UUID)`
- Produces: `BrainProfileService.getOrCreate(UUID brainId): BrainProfile`
- Produces: `BrainProfileService.update(UUID brainId, BrainProfileRequest req): BrainProfile`
- Produces: `GET /api/ai/admin/brains/{brainId}/profile`
- Produces: `PUT /api/ai/admin/brains/{brainId}/profile`

- [ ] **Step 1: Write controller tests**

Create `src/test/java/com/msfg/rag/controller/AdminBrainProfileControllerTest.java`:

```java
package com.msfg.rag.controller;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.dto.BrainProfileRequest;
import com.msfg.rag.service.profile.BrainProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBrainProfileControllerTest {

    private final BrainProfileService service = mock(BrainProfileService.class);
    private final AdminBrainProfileController controller = new AdminBrainProfileController(service);

    private BrainProfile profile(UUID brainId) {
        BrainProfile p = new BrainProfile();
        p.setBrainId(brainId);
        p.setMode(BrainMode.PUBLIC_SITE);
        p.setPurpose("Answer website questions.");
        p.setAudience("public visitor");
        p.setPersonality("Conversational and concise.");
        p.setTone("professional");
        p.setExpertiseLevel("intermediate");
        p.setAnswerLength("balanced");
        p.setConfidenceTarget(0.9);
        p.setClarificationPolicy("Ask one focused question.");
        p.setEscalationPolicy("Escalate low-confidence requests.");
        p.setCitationPolicy("required_when_sources_used");
        p.setCtaPolicy("Recommend pages.");
        p.setDisclaimer("Source-grounded.");
        p.setPublicEnabled(true);
        p.setAllowedDomains(List.of("example.com"));
        return p;
    }

    @Test
    void getReturnsDtoWithoutTokenHash() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId);
        p.setPublicTokenHash("secret-hash");
        when(service.getOrCreate(brainId)).thenReturn(p);

        var dto = controller.get(brainId);

        assertEquals(brainId, dto.brainId());
        assertEquals("PUBLIC_SITE", dto.mode());
        assertEquals(List.of("example.com"), dto.allowedDomains());
        assertEquals(false, dto.toString().contains("secret-hash"));
    }

    @Test
    void putRejectsOutOfRangeConfidenceTarget() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = new BrainProfileRequest(
                "PUBLIC_SITE", "Purpose", "Audience", "Personality", "tone",
                "intermediate", "balanced", 1.2, "clarify", "escalate",
                "required_when_sources_used", "cta", "disclaimer", true, List.of("example.com"));
        assertThrows(IllegalArgumentException.class, () -> controller.put(brainId, req));
    }

    @Test
    void putDelegatesValidRequest() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = new BrainProfileRequest(
                "PUBLIC_SITE", "Purpose", "Audience", "Personality", "tone",
                "intermediate", "balanced", 0.91, "clarify", "escalate",
                "required_when_sources_used", "cta", "disclaimer", true, List.of("example.com"));
        when(service.update(brainId, req)).thenReturn(profile(brainId));

        controller.put(brainId, req);

        verify(service).update(brainId, req);
    }
}
```

Modify `AdminApiKeyFilterTest.gatesDocumentsAndAdminSurfaces()`:

```java
assertFalse(filter.shouldNotFilter(get("/api/ai/admin/brains/00000000-0000-0000-0000-000000000001/profile")));
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests com.msfg.rag.controller.AdminBrainProfileControllerTest \
  --tests com.msfg.rag.config.AdminApiKeyFilterTest
```

Expected: FAIL because DTOs, service, and controller do not exist.

- [ ] **Step 3: Add profile DTOs**

Create `BrainProfileDto`:

```java
package com.msfg.rag.dto;

import com.msfg.rag.domain.BrainProfile;

import java.util.List;
import java.util.UUID;

public record BrainProfileDto(
        UUID brainId,
        String mode,
        String purpose,
        String audience,
        String personality,
        String tone,
        String expertiseLevel,
        String answerLength,
        double confidenceTarget,
        String clarificationPolicy,
        String escalationPolicy,
        String citationPolicy,
        String ctaPolicy,
        String disclaimer,
        boolean publicEnabled,
        List<String> allowedDomains
) {
    public static BrainProfileDto from(BrainProfile p) {
        return new BrainProfileDto(
                p.getBrainId(), p.getMode().name(), p.getPurpose(), p.getAudience(),
                p.getPersonality(), p.getTone(), p.getExpertiseLevel(), p.getAnswerLength(),
                p.getConfidenceTarget(), p.getClarificationPolicy(), p.getEscalationPolicy(),
                p.getCitationPolicy(), p.getCtaPolicy(), p.getDisclaimer(),
                p.isPublicEnabled(), p.getAllowedDomains());
    }
}
```

Create `BrainProfileRequest`:

```java
package com.msfg.rag.dto;

import java.util.List;

public record BrainProfileRequest(
        String mode,
        String purpose,
        String audience,
        String personality,
        String tone,
        String expertiseLevel,
        String answerLength,
        double confidenceTarget,
        String clarificationPolicy,
        String escalationPolicy,
        String citationPolicy,
        String ctaPolicy,
        String disclaimer,
        boolean publicEnabled,
        List<String> allowedDomains
) {
}
```

- [ ] **Step 4: Add `BrainProfileService`**

Create `src/main/java/com/msfg/rag/service/profile/BrainProfileService.java`:

```java
package com.msfg.rag.service.profile;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.dto.BrainProfileRequest;
import com.msfg.rag.repository.BrainProfileRepository;
import com.msfg.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BrainProfileService {

    private final BrainRepository brains;
    private final BrainProfileRepository profiles;

    public BrainProfileService(BrainRepository brains, BrainProfileRepository profiles) {
        this.brains = brains;
        this.profiles = profiles;
    }

    @Transactional
    public BrainProfile getOrCreate(UUID brainId) {
        brains.findById(brainId).orElseThrow(() -> new IllegalArgumentException("Brain not found: " + brainId));
        return profiles.findByBrainId(brainId).orElseGet(() -> {
            BrainProfile profile = new BrainProfile();
            profile.setBrainId(brainId);
            profile.setMode(BrainMode.PUBLIC_SITE);
            profile.setPurpose("Answer questions from approved sources.");
            profile.setAudience("public visitor");
            profile.setPersonality("Conversational, concise, source-grounded assistant.");
            profile.setTone("professional");
            profile.setExpertiseLevel("intermediate");
            profile.setAnswerLength("balanced");
            profile.setConfidenceTarget(0.90);
            profile.setClarificationPolicy("Ask one focused clarifying question when required facts are missing.");
            profile.setEscalationPolicy("Escalate personalized, unsupported, sensitive, or low-confidence requests.");
            profile.setCitationPolicy("required_when_sources_used");
            profile.setCtaPolicy("Recommend relevant pages or a human handoff when useful.");
            profile.setDisclaimer("This answer is generated from approved source context and may be incomplete.");
            profile.setPublicEnabled(true);
            profile.setAllowedDomains(List.of());
            return profiles.save(profile);
        });
    }

    @Transactional
    public BrainProfile update(UUID brainId, BrainProfileRequest req) {
        BrainProfile profile = getOrCreate(brainId);
        profile.setMode(BrainMode.valueOf(required(req.mode(), "mode")));
        profile.setPurpose(required(req.purpose(), "purpose"));
        profile.setAudience(required(req.audience(), "audience"));
        profile.setPersonality(required(req.personality(), "personality"));
        profile.setTone(required(req.tone(), "tone"));
        profile.setExpertiseLevel(required(req.expertiseLevel(), "expertiseLevel"));
        profile.setAnswerLength(required(req.answerLength(), "answerLength"));
        profile.setConfidenceTarget(req.confidenceTarget());
        profile.setClarificationPolicy(required(req.clarificationPolicy(), "clarificationPolicy"));
        profile.setEscalationPolicy(required(req.escalationPolicy(), "escalationPolicy"));
        profile.setCitationPolicy(required(req.citationPolicy(), "citationPolicy"));
        profile.setCtaPolicy(required(req.ctaPolicy(), "ctaPolicy"));
        profile.setDisclaimer(required(req.disclaimer(), "disclaimer"));
        profile.setPublicEnabled(req.publicEnabled());
        profile.setAllowedDomains(req.allowedDomains() == null ? List.of() : req.allowedDomains().stream()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .toList());
        return profiles.save(profile);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }
}
```

- [ ] **Step 5: Add admin controller**

Create `src/main/java/com/msfg/rag/controller/AdminBrainProfileController.java`:

```java
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
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew test --tests com.msfg.rag.controller.AdminBrainProfileControllerTest \
  --tests com.msfg.rag.config.AdminApiKeyFilterTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/msfg/rag/dto/BrainProfileDto.java \
  src/main/java/com/msfg/rag/dto/BrainProfileRequest.java \
  src/main/java/com/msfg/rag/service/profile/BrainProfileService.java \
  src/main/java/com/msfg/rag/controller/AdminBrainProfileController.java \
  src/test/java/com/msfg/rag/controller/AdminBrainProfileControllerTest.java \
  src/test/java/com/msfg/rag/config/AdminApiKeyFilterTest.java
git commit -m "Add brain profile admin API"
```

---

### Task 3: Public Access Token And Domain Validation

**Files:**
- Create: `src/main/java/com/msfg/rag/service/publicapi/PublicAccessService.java`
- Test: `src/test/java/com/msfg/rag/service/publicapi/PublicAccessServiceTest.java`
- Modify: `src/main/java/com/msfg/rag/config/RateLimitFilter.java` if it currently assumes a single public ask route.

**Interfaces:**
- Consumes: `BrainProfileService.getOrCreate(UUID)`
- Produces: `PublicAccessService.validate(UUID brainId, String token, String origin): BrainProfile`
- Produces: `PublicAccessService.rotateToken(UUID brainId): String`
- Uses SHA-256 token hashing through existing `Sha256` helper or `MessageDigest`.

- [ ] **Step 1: Write service tests**

Create `src/test/java/com/msfg/rag/service/publicapi/PublicAccessServiceTest.java`:

```java
package com.msfg.rag.service.publicapi;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.repository.BrainProfileRepository;
import com.msfg.rag.service.profile.BrainProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicAccessServiceTest {

    private final BrainProfileService profiles = mock(BrainProfileService.class);
    private final BrainProfileRepository repository = mock(BrainProfileRepository.class);
    private final PublicAccessService service = new PublicAccessService(profiles, repository);

    private BrainProfile profile(UUID brainId, String token) {
        BrainProfile p = new BrainProfile();
        p.setBrainId(brainId);
        p.setMode(BrainMode.PUBLIC_SITE);
        p.setPublicEnabled(true);
        p.setAllowedDomains(List.of("example.com", "www.example.com"));
        p.setPublicTokenHash(PublicAccessService.hashToken(token));
        return p;
    }

    @Test
    void validateAcceptsMatchingTokenAndAllowedOrigin() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        assertDoesNotThrow(() -> service.validate(brainId, "pub_test", "https://www.example.com/page"));
    }

    @Test
    void validateRejectsBadToken() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        assertThrows(IllegalArgumentException.class,
                () -> service.validate(brainId, "wrong", "https://example.com"));
    }

    @Test
    void validateRejectsUnlistedOrigin() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        assertThrows(IllegalArgumentException.class,
                () -> service.validate(brainId, "pub_test", "https://evil.test"));
    }

    @Test
    void rotateTokenStoresHashAndReturnsPlainTokenOnce() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId, "old");
        when(profiles.getOrCreate(brainId)).thenReturn(p);
        when(repository.save(any(BrainProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        String token = service.rotateToken(brainId);

        assertEquals(true, token.startsWith("rb_pub_"));
        assertEquals(PublicAccessService.hashToken(token), p.getPublicTokenHash());
        verify(repository).save(p);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAccessServiceTest
```

Expected: FAIL because `PublicAccessService` does not exist.

- [ ] **Step 3: Add service**

Create `src/main/java/com/msfg/rag/service/publicapi/PublicAccessService.java`:

```java
package com.msfg.rag.service.publicapi;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.repository.BrainProfileRepository;
import com.msfg.rag.service.profile.BrainProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PublicAccessService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrainProfileService profiles;
    private final BrainProfileRepository repository;

    public PublicAccessService(BrainProfileService profiles, BrainProfileRepository repository) {
        this.profiles = profiles;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BrainProfile validate(UUID brainId, String token, String origin) {
        BrainProfile profile = profiles.getOrCreate(brainId);
        if (!profile.isPublicEnabled() || profile.getMode() != BrainMode.PUBLIC_SITE) {
            throw new IllegalArgumentException("Public access is disabled for this brain");
        }
        if (token == null || token.isBlank() || profile.getPublicTokenHash() == null) {
            throw new IllegalArgumentException("Public token is required");
        }
        if (!MessageDigest.isEqual(hashToken(token).getBytes(StandardCharsets.UTF_8),
                profile.getPublicTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Public token rejected");
        }
        String host = originHost(origin);
        if (!profile.getAllowedDomains().isEmpty() && !profile.getAllowedDomains().contains(host)) {
            throw new IllegalArgumentException("Origin is not allowed for this brain");
        }
        return profile;
    }

    @Transactional
    public String rotateToken(UUID brainId) {
        BrainProfile profile = profiles.getOrCreate(brainId);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = "rb_pub_" + HexFormat.of().formatHex(bytes);
        profile.setPublicTokenHash(hashToken(token));
        repository.save(profile);
        return token;
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String originHost(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("Origin is required");
        }
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Origin host is required");
        }
        return uri.getHost().toLowerCase();
    }
}
```

- [ ] **Step 4: Add admin token rotation endpoint to profile controller**

Add this record and method to `AdminBrainProfileController`:

```java
public record PublicTokenDto(String token) {}

@PostMapping("/public-token")
public PublicTokenDto rotatePublicToken(@PathVariable UUID brainId) {
    return new PublicTokenDto(publicAccessService.rotateToken(brainId));
}
```

Adjust the controller constructor to accept `PublicAccessService publicAccessService`.

Add a controller test:

```java
@Test
void rotatePublicTokenReturnsPlainTokenOnce() {
    UUID brainId = UUID.randomUUID();
    PublicAccessService access = mock(PublicAccessService.class);
    when(access.rotateToken(brainId)).thenReturn("rb_pub_secret");
    AdminBrainProfileController c = new AdminBrainProfileController(service, access);

    assertEquals("rb_pub_secret", c.rotatePublicToken(brainId).token());
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAccessServiceTest \
  --tests com.msfg.rag.controller.AdminBrainProfileControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/msfg/rag/service/publicapi/PublicAccessService.java \
  src/main/java/com/msfg/rag/controller/AdminBrainProfileController.java \
  src/test/java/com/msfg/rag/service/publicapi/PublicAccessServiceTest.java \
  src/test/java/com/msfg/rag/controller/AdminBrainProfileControllerTest.java
git commit -m "Add public ask token validation"
```

---

### Task 4: Clarification Engine And Public Ask Contract

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/PublicAskRequest.java`
- Create: `src/main/java/com/msfg/rag/dto/PublicAskResponse.java`
- Create: `src/main/java/com/msfg/rag/dto/PublicRecommendedPageDto.java`
- Create: `src/main/java/com/msfg/rag/dto/ClarificationQuestionDto.java`
- Create: `src/main/java/com/msfg/rag/service/clarification/ClarificationDecision.java`
- Create: `src/main/java/com/msfg/rag/service/clarification/ClarificationService.java`
- Create: `src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java`
- Create: `src/main/java/com/msfg/rag/controller/PublicAskController.java`
- Test: `src/test/java/com/msfg/rag/service/clarification/ClarificationServiceTest.java`
- Test: `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`

**Interfaces:**
- Consumes: `AskService.ask(AskRequest, UUID)`
- Consumes: `PageGuideService.collect/OutputContractService` through existing ask path
- Produces: `POST /api/ai/public/{slug}/ask`
- Produces: `ClarificationService.decide(UUID brainId, String question, String surface, Map<String, Object> facts): ClarificationDecision`

- [ ] **Step 1: Write clarification service tests**

Create `src/test/java/com/msfg/rag/service/clarification/ClarificationServiceTest.java`:

```java
package com.msfg.rag.service.clarification;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.ClarificationRule;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.repository.ClarificationRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClarificationServiceTest {

    private final ClarificationRuleRepository rules = mock(ClarificationRuleRepository.class);
    private final ClarificationService service = new ClarificationService(rules);

    @Test
    void asksForFirstMissingRequiredFact() {
        ClarificationRule rule = new ClarificationRule();
        rule.setTopic("eligibility");
        rule.setIntent("ELIGIBILITY");
        rule.setRequiredFacts(List.of("occupancy", "propertyType"));
        rule.setQuestion("Is this for a primary residence?");
        rule.setPriority(10);
        rule.setRequiredForPublic(true);
        when(rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID)).thenReturn(List.of(rule));

        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Can I use FHA for a duplex?", "PUBLIC", Map.of("propertyType", "duplex"));

        assertEquals(ResponseType.CLARIFY, decision.responseType());
        assertEquals(List.of("occupancy"), decision.missingFacts());
        assertEquals("Is this for a primary residence?", decision.question());
    }

    @Test
    void answersWhenRequiredFactsArePresent() {
        ClarificationRule rule = new ClarificationRule();
        rule.setTopic("eligibility");
        rule.setIntent("ELIGIBILITY");
        rule.setRequiredFacts(List.of("occupancy"));
        rule.setQuestion("Is this for a primary residence?");
        rule.setRequiredForPublic(true);
        when(rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(TestBrains.DEFAULT_ID)).thenReturn(List.of(rule));

        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Can I use FHA for a duplex?", "PUBLIC", Map.of("occupancy", "primary"));

        assertEquals(ResponseType.ANSWER, decision.responseType());
        assertTrue(decision.missingFacts().isEmpty());
    }

    @Test
    void navigatesForPageFindingIntent() {
        ClarificationDecision decision = service.decide(
                TestBrains.DEFAULT_ID, "Where do I apply?", "PUBLIC", Map.of());

        assertEquals(ResponseType.NAVIGATE, decision.responseType());
    }
}
```

- [ ] **Step 2: Write public ask service tests**

Create `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`:

```java
package com.msfg.rag.service.publicapi;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.PublicAskRequest;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.clarification.ClarificationDecision;
import com.msfg.rag.service.clarification.ClarificationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicAskServiceTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final PublicAccessService access = mock(PublicAccessService.class);
    private final ClarificationService clarification = mock(ClarificationService.class);
    private final AskService ask = mock(AskService.class);
    private final PublicAskService service = new PublicAskService(brains, access, clarification, ask);

    @Test
    void clarifyResponseDoesNotCallAnswerModel() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I use FHA?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "Is this for a primary residence?", List.of("occupancy"), Map.of("rule", "eligibility")));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I use FHA?", "/", "PUBLIC", Map.of()));

        assertEquals("CLARIFY", response.responseType());
        assertEquals("Is this for a primary residence?", response.clarifyingQuestion());
        verify(ask, never()).ask(any(), any());
    }

    @Test
    void answerResponseMapsExistingAskResponse() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "PUBLIC", Map.of()));

        assertEquals("ANSWER", response.responseType());
        assertEquals("PMI is mortgage insurance.", response.answer());
        assertEquals(0.94, response.confidence());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.clarification.ClarificationServiceTest \
  --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
```

Expected: FAIL because the public ask DTOs and services do not exist.

- [ ] **Step 4: Add public DTOs**

Create `PublicAskRequest`:

```java
package com.msfg.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record PublicAskRequest(
        @NotBlank @Size(max = 255) String sessionId,
        @NotBlank @Size(max = 2000) String message,
        @Size(max = 200) String pageRoute,
        @Size(max = 20) String surface,
        Map<String, Object> facts
) {
}
```

Create `ClarificationQuestionDto`:

```java
package com.msfg.rag.dto;

import java.util.List;

public record ClarificationQuestionDto(
        String question,
        List<String> missingFacts
) {
}
```

Create `PublicRecommendedPageDto`:

```java
package com.msfg.rag.dto;

public record PublicRecommendedPageDto(
        String label,
        String url,
        String reason
) {
}
```

Create `PublicAskResponse`:

```java
package com.msfg.rag.dto;

import java.util.List;
import java.util.UUID;

public record PublicAskResponse(
        String responseType,
        String message,
        String answer,
        String clarifyingQuestion,
        List<String> missingFacts,
        List<CitationDto> citations,
        List<PublicRecommendedPageDto> recommendedPages,
        double confidence,
        String nextAction,
        UUID conversationId,
        UUID traceId
) {
}
```

- [ ] **Step 5: Add clarification decision and service**

Create `ClarificationDecision`:

```java
package com.msfg.rag.service.clarification;

import com.msfg.rag.domain.ResponseType;

import java.util.List;
import java.util.Map;

public record ClarificationDecision(
        ResponseType responseType,
        String question,
        List<String> missingFacts,
        Map<String, Object> reason
) {
    public static ClarificationDecision answer() {
        return new ClarificationDecision(ResponseType.ANSWER, null, List.of(), Map.of("decision", "answer"));
    }
}
```

Create `ClarificationService`:

```java
package com.msfg.rag.service.clarification;

import com.msfg.rag.domain.ClarificationRule;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.repository.ClarificationRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ClarificationService {

    private final ClarificationRuleRepository rules;

    public ClarificationService(ClarificationRuleRepository rules) {
        this.rules = rules;
    }

    public ClarificationDecision decide(UUID brainId, String question, String surface, Map<String, Object> facts) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.US);
        if (normalized.contains("where do i") || normalized.contains("how do i apply")
                || normalized.contains("show me") || normalized.contains("page")) {
            return new ClarificationDecision(ResponseType.NAVIGATE, null, List.of(), Map.of("decision", "navigation intent"));
        }
        Map<String, Object> knownFacts = facts == null ? Map.of() : facts;
        boolean publicSurface = surface == null || surface.isBlank() || "PUBLIC".equalsIgnoreCase(surface);
        for (ClarificationRule rule : rules.findByBrainIdAndActiveTrueOrderByPriorityAsc(brainId)) {
            if (publicSurface && !rule.isRequiredForPublic()) {
                continue;
            }
            List<String> missing = rule.getRequiredFacts().stream()
                    .filter(f -> !knownFacts.containsKey(f) || String.valueOf(knownFacts.get(f)).isBlank())
                    .toList();
            if (!missing.isEmpty() && questionMatches(rule, normalized)) {
                return new ClarificationDecision(ResponseType.CLARIFY, rule.getQuestion(), List.of(missing.getFirst()),
                        Map.of("topic", rule.getTopic(), "intent", rule.getIntent()));
            }
        }
        return ClarificationDecision.answer();
    }

    private static boolean questionMatches(ClarificationRule rule, String normalized) {
        String topic = rule.getTopic() == null ? "" : rule.getTopic().toLowerCase(Locale.US);
        String intent = rule.getIntent() == null ? "" : rule.getIntent().toLowerCase(Locale.US);
        return normalized.contains(topic)
                || normalized.contains(intent)
                || normalized.contains("qualify")
                || normalized.contains("eligible")
                || normalized.contains("can i");
    }
}
```

- [ ] **Step 6: Add public ask service and controller**

Create `PublicAskService`:

```java
package com.msfg.rag.service.publicapi;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.PublicAskRequest;
import com.msfg.rag.dto.PublicAskResponse;
import com.msfg.rag.dto.PublicRecommendedPageDto;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.clarification.ClarificationDecision;
import com.msfg.rag.service.clarification.ClarificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PublicAskService {

    private final BrainRepository brains;
    private final PublicAccessService access;
    private final ClarificationService clarification;
    private final AskService askService;

    public PublicAskService(BrainRepository brains, PublicAccessService access,
                            ClarificationService clarification, AskService askService) {
        this.brains = brains;
        this.access = access;
        this.clarification = clarification;
        this.askService = askService;
    }

    public PublicAskResponse ask(String slug, String token, String origin, PublicAskRequest req) {
        Brain brain = brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
        access.validate(brain.getId(), token, origin);
        String surface = req.surface() == null || req.surface().isBlank() ? "PUBLIC" : req.surface();
        ClarificationDecision decision = clarification.decide(
                brain.getId(), req.message(), surface, req.facts() == null ? Map.of() : req.facts());
        if (decision.responseType() == ResponseType.CLARIFY) {
            return new PublicAskResponse("CLARIFY", decision.question(), null, decision.question(),
                    decision.missingFacts(), List.of(), List.of(), 0.0, null, null, null);
        }
        if (decision.responseType() == ResponseType.NAVIGATE) {
            AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId());
            return mapAnswer("NAVIGATE", answer);
        }
        AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId());
        return mapAnswer(answer.humanEscalationRequired() ? "ESCALATE" : "ANSWER", answer);
    }

    private static AskRequest toAskRequest(PublicAskRequest req, String surface) {
        return new AskRequest(null, req.sessionId(), req.message(), null, null, req.pageRoute(), surface);
    }

    private static PublicAskResponse mapAnswer(String responseType, AskResponse answer) {
        List<PublicRecommendedPageDto> pages = answer.recommendedPage() == null
                ? List.of()
                : List.of(new PublicRecommendedPageDto(
                        answer.recommendedPage().label(), answer.recommendedPage().route(), "Matched the current question."));
        return new PublicAskResponse(responseType, answer.answer(), answer.answer(), null, List.of(),
                answer.citations(), pages, answer.confidence(), answer.nextAction(), answer.conversationId(), answer.traceId());
    }
}
```

Create `PublicAskController`:

```java
package com.msfg.rag.controller;

import com.msfg.rag.dto.PublicAskRequest;
import com.msfg.rag.dto.PublicAskResponse;
import com.msfg.rag.service.publicapi.PublicAskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/public/{slug}")
public class PublicAskController {

    private final PublicAskService service;

    public PublicAskController(PublicAskService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public ResponseEntity<PublicAskResponse> ask(@PathVariable String slug,
                                                 @RequestHeader("X-Public-Brain-Token") String token,
                                                 @RequestHeader("Origin") String origin,
                                                 @Valid @RequestBody PublicAskRequest request) {
        return ResponseEntity.ok(service.ask(slug, token, origin, request));
    }
}
```

- [ ] **Step 7: Run tests**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.clarification.ClarificationServiceTest \
  --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/msfg/rag/dto/PublicAskRequest.java \
  src/main/java/com/msfg/rag/dto/PublicAskResponse.java \
  src/main/java/com/msfg/rag/dto/PublicRecommendedPageDto.java \
  src/main/java/com/msfg/rag/dto/ClarificationQuestionDto.java \
  src/main/java/com/msfg/rag/service/clarification/ClarificationDecision.java \
  src/main/java/com/msfg/rag/service/clarification/ClarificationService.java \
  src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java \
  src/main/java/com/msfg/rag/controller/PublicAskController.java \
  src/test/java/com/msfg/rag/service/clarification/ClarificationServiceTest.java \
  src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java
git commit -m "Add public ask clarification contract"
```

---

### Task 5: Source Visibility Enforcement In Retrieval

**Files:**
- Modify: `src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java`
- Modify: `src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java`
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java`
- Modify: `src/test/java/com/msfg/rag/repository/HybridSearchIntegrationTest.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

**Interfaces:**
- Produces: `RetrievalService.retrieve(String question, UUID brainId, SourceVisibility visibility)`
- Keeps: `RetrievalService.retrieve(String question, UUID brainId)` delegating to `SourceVisibility.INTERNAL` or an unfiltered admin-safe mode.
- Repository SQL must filter `d.visibility = :visibility` and `d.trust_level <> 'BLOCKED'` when public visibility is requested.

- [ ] **Step 1: Write repository visibility test**

In `HybridSearchIntegrationTest`, add a test that creates one `PUBLIC` document and one `INTERNAL` document with matching chunks, then calls the new public retrieval method and asserts the internal source is absent:

```java
@Test
void publicRetrievalExcludesInternalDocuments() {
    UUID brainId = TestBrains.DEFAULT_ID;
    MortgageDocument publicDoc = saveDocument(brainId, "Public Doc", SourceVisibility.PUBLIC, SourceTrustLevel.APPROVED);
    MortgageDocument internalDoc = saveDocument(brainId, "Internal Doc", SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED);
    saveChunk(publicDoc, "PMI public content", onesEmbedding());
    saveChunk(internalDoc, "PMI internal content", onesEmbedding());

    RetrievalResult result = retrievalService.retrieve("PMI", brainId, SourceVisibility.PUBLIC);

    assertTrue(result.chunks().stream().anyMatch(c -> c.documentTitle().equals("Public Doc")));
    assertTrue(result.chunks().stream().noneMatch(c -> c.documentTitle().equals("Internal Doc")));
}
```

Add helper methods in the same test class using existing entity/repository patterns:

```java
private MortgageDocument saveDocument(UUID brainId, String title,
                                      SourceVisibility visibility, SourceTrustLevel trust) {
    MortgageDocument d = new MortgageDocument();
    d.setBrainId(brainId);
    d.setTitle(title);
    d.setSourceName(title);
    d.setSourceType(SourceType.EDUCATIONAL);
    d.setFileName(title + ".txt");
    d.setVisibility(visibility);
    d.setTrustLevel(trust);
    return documents.saveAndFlush(d);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.HybridSearchIntegrationTest
```

Expected: FAIL because `retrieve(String, UUID, SourceVisibility)` and repository visibility filtering do not exist.

- [ ] **Step 3: Extend repository SQL**

Add `@Param("visibility") String visibility` to `searchByVector` and `searchByKeyword`. Add this predicate to both SQL queries:

```sql
AND d.visibility = :visibility
AND d.trust_level <> 'BLOCKED'
```

Keep the original method names but change signatures:

```java
List<ChunkSearchResult> searchByVector(@Param("embedding") String embedding,
                                       @Param("limit") int limit,
                                       @Param("brainId") UUID brainId,
                                       @Param("visibility") String visibility);

List<ChunkSearchResult> searchByKeyword(@Param("query") String query,
                                        @Param("limit") int limit,
                                        @Param("brainId") UUID brainId,
                                        @Param("visibility") String visibility);
```

- [ ] **Step 4: Add retrieval overload**

Modify `RetrievalService`:

```java
@Transactional(readOnly = true)
public RetrievalResult retrieve(String question, UUID brainId) {
    return retrieve(question, brainId, SourceVisibility.PUBLIC);
}

@Transactional(readOnly = true)
public RetrievalResult retrieve(String question, UUID brainId, SourceVisibility visibility) {
    // move existing method body here
}
```

Update repository calls:

```java
List<ChunkSearchResult> vectorHits =
        chunkRepository.searchByVector(vectorLiteral, candidatePool, brainId, visibility.name());
List<ChunkSearchResult> keywordHits = chunkRepository.searchByKeyword(
        toOrQuery(expandedQuestion), candidatePool, brainId, visibility.name());
```

- [ ] **Step 5: Thread public visibility through `PublicAskService`**

The existing `AskService.ask` calls `RetrievalService.retrieve(question, brainId)`. Add an overload:

```java
public AskResponse ask(AskRequest request, UUID brainId, SourceVisibility visibility)
```

Move the current method body into the overload and replace the retrieval call with:

```java
RetrievalResult retrieval = retrievalService.retrieve(request.question(), brainId, visibility);
```

Keep the original method:

```java
@Transactional
public AskResponse ask(AskRequest request, UUID brainId) {
    return ask(request, brainId, SourceVisibility.PUBLIC);
}
```

Change `PublicAskService` to call:

```java
askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC)
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.HybridSearchIntegrationTest \
  --tests com.msfg.rag.service.AskServiceTest \
  --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java \
  src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java \
  src/main/java/com/msfg/rag/service/AskService.java \
  src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java \
  src/test/java/com/msfg/rag/repository/HybridSearchIntegrationTest.java \
  src/test/java/com/msfg/rag/service/AskServiceTest.java \
  src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java
git commit -m "Enforce public source visibility in retrieval"
```

---

### Task 6: Trace Expansion For Public Decisions

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/audit/RagTraceService.java`
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java`
- Test: `src/test/java/com/msfg/rag/service/AskServiceTest.java`
- Test: `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`

**Interfaces:**
- Produces: `RagTraceService.record(..., ResponseType responseType, ClarificationDecision clarificationDecision, SourceVisibility visibility, Map<String,Object> confidenceReason, String validationOutcome)`
- Public clarify responses record a trace row with `response_type = CLARIFY`, missing facts, and no retrieved context.

- [ ] **Step 1: Add tests for trace metadata**

In `PublicAskServiceTest`, add:

```java
@Test
void clarificationRecordsPublicTrace() {
    Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
    when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
    when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
            .thenReturn(new BrainProfile());
    when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I qualify?"), eq("PUBLIC"), any()))
            .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                    "What is the occupancy?", List.of("occupancy"), Map.of("topic", "eligibility")));

    service.ask("generic", "token", "https://example.com",
            new PublicAskRequest("s1", "Can I qualify?", "/", "PUBLIC", Map.of()));

    verify(traceService).recordPublicDecision(eq(TestBrains.DEFAULT_ID), eq("s1"),
            eq("Can I qualify?"), any(), eq(SourceVisibility.PUBLIC));
}
```

This requires adding `RagTraceService traceService` to the `PublicAskService` constructor in the test fixture.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest \
  --tests com.msfg.rag.service.AskServiceTest
```

Expected: FAIL because trace overloads and public decision recording do not exist.

- [ ] **Step 3: Add trace service methods**

Add to `RagTraceService`:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public RagTrace recordPublicDecision(UUID brainId,
                                     String sessionId,
                                     String userQuestion,
                                     ClarificationDecision decision,
                                     SourceVisibility visibility) {
    RagTrace trace = new RagTrace();
    trace.setBrainId(brainId);
    trace.setUserQuestion(userQuestion);
    trace.setResponseType(decision.responseType().name());
    trace.setClarificationDecision(decision.reason());
    trace.setMissingFacts(decision.missingFacts());
    trace.setCollectedFacts(Map.of("session_id", sessionId));
    trace.setVisibilityFilter(visibility.name());
    trace.setConfidenceReason(Map.of("reason", "pre-retrieval public decision"));
    trace.setValidationOutcome("not_applicable");
    trace.setHumanEscalationRequired(decision.responseType() == ResponseType.ESCALATE);
    return repository.save(trace);
}
```

Extend the existing `record(...)` method signature to accept `ResponseType responseType`, `ClarificationDecision clarificationDecision`, `SourceVisibility visibility`, `Map<String, Object> confidenceReason`, and `String validationOutcome`. Set defaults inside the method when callers pass null:

```java
trace.setResponseType(responseType == null ? ResponseType.ANSWER.name() : responseType.name());
trace.setClarificationDecision(clarificationDecision == null ? Map.of("decision", "answer") : clarificationDecision.reason());
trace.setMissingFacts(clarificationDecision == null ? List.of() : clarificationDecision.missingFacts());
trace.setVisibilityFilter(visibility == null ? SourceVisibility.PUBLIC.name() : visibility.name());
trace.setConfidenceReason(confidenceReason == null ? Map.of("confidence", confidence == null ? 0.0 : confidence) : confidenceReason);
trace.setValidationOutcome(validationOutcome == null ? "valid" : validationOutcome);
```

- [ ] **Step 4: Update callers**

In `AskService`, pass:

```java
ResponseType.ANSWER,
ClarificationDecision.answer(),
visibility,
Map.of("retrieval_confidence", retrieval.confidence(), "source_count", retrieval.chunks().size()),
"valid"
```

For refusal paths, pass `ResponseType.ESCALATE` and `validationOutcome` equal to the refusal reason.

In `PublicAskService`, call `traceService.recordPublicDecision(...)` for `CLARIFY` responses before returning the response and include `trace.getId()` in the `PublicAskResponse`.

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest \
  --tests com.msfg.rag.service.AskServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/msfg/rag/service/audit/RagTraceService.java \
  src/main/java/com/msfg/rag/service/AskService.java \
  src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java \
  src/test/java/com/msfg/rag/service/AskServiceTest.java \
  src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java
git commit -m "Trace public ask decisions"
```

---

### Task 7: Dashboard Personality Screen And Public Test Console

**Files:**
- Modify: `dashboard/src/types.ts`
- Modify: `dashboard/src/api.ts`
- Modify: `dashboard/src/App.tsx`
- Modify: `src/main/java/com/msfg/rag/controller/AdminStatsController.java`
- Modify: `src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java`
- Create: `dashboard/src/screens/Personality.tsx`
- Modify: `dashboard/src/screens/TestConsole.tsx`

**Interfaces:**
- Consumes: `GET /api/ai/admin/brains/{brainId}/profile`
- Consumes: `PUT /api/ai/admin/brains/{brainId}/profile`
- Consumes: `POST /api/ai/admin/brains/{brainId}/profile/public-token`
- Consumes: `POST /api/ai/public/{slug}/ask`

- [ ] **Step 1: Add dashboard types**

In `dashboard/src/types.ts`, add:

```ts
export interface BrainProfileDto {
  brainId: string;
  mode: "PUBLIC_SITE" | "PRIVATE_SITE" | "SECURE_DEPLOYMENT";
  purpose: string;
  audience: string;
  personality: string;
  tone: string;
  expertiseLevel: string;
  answerLength: string;
  confidenceTarget: number;
  clarificationPolicy: string;
  escalationPolicy: string;
  citationPolicy: string;
  ctaPolicy: string;
  disclaimer: string;
  publicEnabled: boolean;
  allowedDomains: string[];
}

export type BrainProfileRequest = Omit<BrainProfileDto, "brainId">;

export interface PublicAskRequest {
  sessionId: string;
  message: string;
  pageRoute: string | null;
  surface: "PUBLIC" | "INTERNAL" | "SECURE";
  facts: Record<string, unknown>;
}

export interface PublicRecommendedPage {
  label: string;
  url: string;
  reason: string;
}

export interface PublicAskResponse {
  responseType: "ANSWER" | "CLARIFY" | "NAVIGATE" | "ESCALATE";
  message: string | null;
  answer: string | null;
  clarifyingQuestion: string | null;
  missingFacts: string[];
  citations: Citation[];
  recommendedPages: PublicRecommendedPage[];
  confidence: number;
  nextAction: string | null;
  conversationId: string | null;
  traceId: string | null;
}
```

- [ ] **Step 2: Add API helpers**

In `dashboard/src/api.ts`, import the new types and add:

```ts
export const profileApi = {
  get: (brainId: string) => api.get<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`),
  update: (brainId: string, body: BrainProfileRequest) =>
    api.put<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`, body),
  rotatePublicToken: (brainId: string) =>
    api.post<{ token: string }>(`/api/ai/admin/brains/${brainId}/profile/public-token`, {}),
};

export async function publicAsk(slug: string, token: string, origin: string, body: PublicAskRequest) {
  const headers = new Headers();
  headers.set("X-Public-Brain-Token", token);
  headers.set("Origin", origin);
  headers.set("Content-Type", "application/json");
  const response = await fetch(`/api/ai/public/${slug}/ask`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => null) as { error?: string } | null;
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return response.json() as Promise<PublicAskResponse>;
}
```

- [ ] **Step 3: Expose active brain id in admin stats**

Modify `AdminStatsController.BrainDto`:

```java
public record BrainDto(UUID id, String companyName, String slug) {}
```

Modify the stats response construction:

```java
return new StatsDto(
        new BrainDto(brainId, pack.companyName(), pack.slug()),
        new CorpusDto(documents.countByBrainIdAndActiveTrue(brainId),
                      documents.countByBrainId(brainId),
                      chunks.countByBrainId(brainId)));
```

Modify `AdminStatsControllerTest` to assert the id is present:

```java
assertEquals(TestBrains.DEFAULT_ID, body.brain().id());
```

Modify `dashboard/src/types.ts`:

```ts
export interface Stats {
  brain: { id: string; companyName: string; slug: string };
  corpus: { activeDocuments: number; totalDocuments: number; chunks: number };
}
```

- [ ] **Step 4: Create Personality screen**

Create `dashboard/src/screens/Personality.tsx` with a form bound to the active/default brain. The screen must expose:

- mode
- purpose
- audience
- personality
- tone
- expertise level
- answer length
- confidence target
- clarification policy
- escalation policy
- citation policy
- CTA policy
- disclaimer
- public enabled
- allowed domains
- rotate public token button

Use this initial component shape:

```tsx
import { useEffect, useState } from "react";
import { profileApi } from "../api";
import { BrainProfileDto, BrainProfileRequest, Stats } from "../types";
import { ErrorNote, Pill } from "../components";

export default function Personality({ stats }: { stats: Stats | null }) {
  const [profile, setProfile] = useState<BrainProfileDto | null>(null);
  const [draft, setDraft] = useState<BrainProfileRequest | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    const brainId = stats?.brain.id;
    if (!brainId) return;
    profileApi.get(brainId)
      .then((p) => { setProfile(p); setDraft({ ...p }); })
      .catch((e) => setError((e as Error).message));
  }, [stats]);

  if (!draft) return <h1>Personality</h1>;

  const set = <K extends keyof BrainProfileRequest>(key: K, value: BrainProfileRequest[K]) =>
    setDraft({ ...draft, [key]: value });

  async function save() {
    const brainId = profile?.brainId;
    if (!brainId) return;
    setError(null); setSaved(false);
    try {
      const updated = await profileApi.update(brainId, draft);
      setProfile(updated); setDraft({ ...updated }); setSaved(true);
    } catch (e) { setError((e as Error).message); }
  }

  async function rotate() {
    const brainId = profile?.brainId;
    if (!brainId) return;
    setError(null);
    try { setToken((await profileApi.rotatePublicToken(brainId)).token); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Personality</h1>
        <span className="muted">control public assistant behavior for the active brain</span>
      </header>
      <ErrorNote message={error} />
      <div className="card">
        <div className="setting-row">
          <label>Mode</label>
          <select value={draft.mode} onChange={(e) => set("mode", e.target.value as BrainProfileRequest["mode"])}>
            <option value="PUBLIC_SITE">public website</option>
            <option value="PRIVATE_SITE">private website</option>
            <option value="SECURE_DEPLOYMENT">secure deployment</option>
          </select>
        </div>
        <div className="setting-row"><label>Purpose</label><textarea value={draft.purpose} onChange={(e) => set("purpose", e.target.value)} /></div>
        <div className="setting-row"><label>Audience</label><input value={draft.audience} onChange={(e) => set("audience", e.target.value)} /></div>
        <div className="setting-row"><label>Personality</label><textarea value={draft.personality} onChange={(e) => set("personality", e.target.value)} /></div>
        <div className="setting-row"><label>Tone</label><input value={draft.tone} onChange={(e) => set("tone", e.target.value)} /></div>
        <div className="setting-row"><label>Expertise level</label><input value={draft.expertiseLevel} onChange={(e) => set("expertiseLevel", e.target.value)} /></div>
        <div className="setting-row"><label>Answer length</label><input value={draft.answerLength} onChange={(e) => set("answerLength", e.target.value)} /></div>
        <div className="setting-row"><label>Confidence target</label><input value={String(draft.confidenceTarget)} onChange={(e) => set("confidenceTarget", Number(e.target.value))} /></div>
        <div className="setting-row"><label>Clarification policy</label><textarea value={draft.clarificationPolicy} onChange={(e) => set("clarificationPolicy", e.target.value)} /></div>
        <div className="setting-row"><label>Escalation policy</label><textarea value={draft.escalationPolicy} onChange={(e) => set("escalationPolicy", e.target.value)} /></div>
        <div className="setting-row"><label>Citation policy</label><input value={draft.citationPolicy} onChange={(e) => set("citationPolicy", e.target.value)} /></div>
        <div className="setting-row"><label>CTA policy</label><textarea value={draft.ctaPolicy} onChange={(e) => set("ctaPolicy", e.target.value)} /></div>
        <div className="setting-row"><label>Disclaimer</label><textarea value={draft.disclaimer} onChange={(e) => set("disclaimer", e.target.value)} /></div>
        <div className="setting-row">
          <label>Public enabled</label>
          <select value={String(draft.publicEnabled)} onChange={(e) => set("publicEnabled", e.target.value === "true")}>
            <option value="true">enabled</option>
            <option value="false">disabled</option>
          </select>
        </div>
        <div className="setting-row">
          <label>Allowed domains</label>
          <textarea value={draft.allowedDomains.join("\n")} onChange={(e) =>
            set("allowedDomains", e.target.value.split("\n").map((s) => s.trim()).filter(Boolean))} />
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={save}>Save profile</button>
          <button onClick={rotate}>Rotate public token</button>
          {saved && <Pill tone="green">saved</Pill>}
        </div>
        {token && <p className="muted">New public token: <code>{token}</code></p>}
      </div>
    </>
  );
}
```

- [ ] **Step 5: Wire navigation**

Modify `App.tsx`:

```tsx
import Personality from "./screens/Personality";
```

Add nav link:

```tsx
<NavLink to="/personality">Personality</NavLink>
```

Add route:

```tsx
<Route path="/personality" element={<Personality stats={stats} />} />
```

- [ ] **Step 6: Add public mode to Test Console**

Modify `TestConsole.tsx` to include a third mode `public`. Add inputs for public token and origin. In public mode call `publicAsk(slug, token, origin, {...})` and render `responseType`, `clarifyingQuestion`, `missingFacts`, `recommendedPages`, and citations.

Use this render rule:

```tsx
{publicAnswer && (
  <div className="card">
    <div className="chips">
      <Pill tone={publicAnswer.responseType === "ANSWER" ? "green" : publicAnswer.responseType === "CLARIFY" ? "amber" : "gray"}>
        {publicAnswer.responseType.toLowerCase()}
      </Pill>
      <Pill tone="gray">confidence {publicAnswer.confidence.toFixed(2)}</Pill>
      {publicAnswer.traceId && <Pill tone="gray">trace {publicAnswer.traceId.slice(0, 8)}</Pill>}
    </div>
    {publicAnswer.clarifyingQuestion && <p className="answer">{publicAnswer.clarifyingQuestion}</p>}
    {publicAnswer.answer && <p className="answer">{publicAnswer.answer}</p>}
    {publicAnswer.missingFacts.length > 0 && <p className="muted">Missing: {publicAnswer.missingFacts.join(", ")}</p>}
    {publicAnswer.recommendedPages.length > 0 && (
      <ul className="citations">
        {publicAnswer.recommendedPages.map((p) => <li key={p.url}>{p.label} ({p.url}) - {p.reason}</li>)}
      </ul>
    )}
  </div>
)}
```

- [ ] **Step 7: Build dashboard**

Run:

```bash
cd dashboard && npm run build
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/msfg/rag/controller/AdminStatsController.java \
  src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java \
  dashboard/src/types.ts dashboard/src/api.ts dashboard/src/App.tsx \
  dashboard/src/screens/Personality.tsx dashboard/src/screens/TestConsole.tsx
git commit -m "Add public assistant dashboard controls"
```

---

### Task 8: Foundation Verification And Docs

**Files:**
- Modify: `README.md`
- Modify: `.env.example`
- Create: `docs/public-assistant.md`

**Interfaces:**
- Documents `X-Public-Brain-Token`
- Documents `/api/ai/public/{slug}/ask`
- Documents source visibility behavior

- [ ] **Step 1: Update `.env.example`**

Add comments only; public tokens are generated by dashboard/API and must not be static env keys:

```dotenv
# Public website assistant tokens are generated per brain from the dashboard.
# They are stored hashed in PostgreSQL and are separate from ADMIN_API_KEY.
```

- [ ] **Step 2: Update README**

Add this section:

````markdown
## Public Website Assistant

Public website calls use a per-brain public token, not `ADMIN_API_KEY`.

1. Open the dashboard.
2. Go to Personality.
3. Add allowed domains for the active brain.
4. Rotate a public token and store it in the website environment.
5. Test with:

```bash
curl -X POST http://localhost:8091/api/ai/public/generic/ask \
  -H "Content-Type: application/json" \
  -H "Origin: http://localhost:5174" \
  -H "X-Public-Brain-Token: $PUBLIC_BRAIN_TOKEN" \
  -d '{
    "sessionId": "hero-test",
    "message": "What can you help me with?",
    "pageRoute": "/",
    "surface": "PUBLIC",
    "facts": {}
  }'
```

Public requests only retrieve `PUBLIC` sources. Internal or secure sources are filtered before prompt assembly.
````

- [ ] **Step 3: Create public assistant docs page**

Create `docs/public-assistant.md`:

````markdown
# Public Assistant

The public assistant endpoint is for website visitors. It uses a per-brain public token and allowed-domain checks. It does not accept `ADMIN_API_KEY` and it cannot mutate sources, prompts, profiles, or admin settings.

Endpoint:

```text
POST /api/ai/public/{slug}/ask
```

Required headers:

```text
Origin: https://example.com
X-Public-Brain-Token: rb_pub_...
Content-Type: application/json
```

Request:

```json
{
  "sessionId": "hero-test",
  "message": "What can you help me with?",
  "pageRoute": "/",
  "surface": "PUBLIC",
  "facts": {}
}
```

Response types:

- `ANSWER`: source-grounded answer.
- `CLARIFY`: one missing fact is needed before a high-confidence answer.
- `NAVIGATE`: the user mainly needs a relevant page or next step.
- `ESCALATE`: the system should hand off to a human.

Public requests retrieve only `PUBLIC` sources. Internal and secure sources are filtered before prompt assembly.
````

- [ ] **Step 4: Full backend verification**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 5: Full dashboard verification**

Run:

```bash
cd dashboard && npm run build
```

Expected: PASS.

- [ ] **Step 6: Whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add README.md .env.example docs/public-assistant.md
git commit -m "Document public website assistant setup"
```

---

## Self-Review Checklist

- Spec coverage: This plan covers brain profiles, public ask contract, public token/domain validation, clarification routing, source visibility enforcement, trace expansion, dashboard personality controls, and public test-console support.
- Follow-on plan required: URL learning MVP, source versions, learning source manager, feedback events, evaluation harness, and website widget packaging should each be planned after this foundation is merged and verified.
- Red-flag scan: the plan avoids unfinished markers and vague implementation instructions.
- Type consistency: `ResponseType`, `SourceVisibility`, `SourceTrustLevel`, `BrainProfile`, `ClarificationDecision`, `PublicAskRequest`, and `PublicAskResponse` names are consistent across tasks.
- Security coverage: public token is separate from admin key; admin endpoints remain under `/api/ai/admin`; public retrieval uses `SourceVisibility.PUBLIC`.
