# Generic Agentic Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Complete the approved cleanup pass: dead-code/dependency cleanup, generic package/domain naming, deeper agentic retrieval, and ingestion quality evaluation.

**Architecture:** Keep stable database/API behavior while moving Java code to `com.ragbrain`, renaming document types to `BrainDocument`, adding bounded multi-step retrieval inside `AgenticRetrievalService`, and exposing read-only ingestion quality reports through admin APIs. Avoid broad UI redesign; add only the dashboard surface needed to inspect ingestion quality.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL/pgvector, Flyway, React 18, Vite, Vitest.

## Global Constraints

- Work only in `/Users/zacharyzink/rag-brain`.
- Preserve existing Flyway migrations; add new migrations only when schema changes are required.
- Keep mortgage/MSFG content only in example packs, example docs, and intentional sample-pack tests.
- Use `com.ragbrain` for Java package root.
- Use TDD for behavior changes.
- Run `./gradlew test --rerun-tasks`, `npm run check`, `npm test -- --run`, and `npm run build` before completion.

---

### Task 1: Generic Java Package And Document Type Rename

**Files:**
- Move: `src/main/java/com/msfg/rag/**` -> `src/main/java/com/ragbrain/rag/**`
- Move: `src/test/java/com/msfg/rag/**` -> `src/test/java/com/ragbrain/rag/**`
- Rename: `MsfgRagApplication.java` -> `RagBrainApplication.java`
- Rename: `MortgageDocument.java` -> `BrainDocument.java`
- Rename: `MortgageDocumentRepository.java` -> `BrainDocumentRepository.java`
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: package root `com.ragbrain`; boot class `RagBrainApplication`; entity `BrainDocument`; repository `BrainDocumentRepository`.

- [x] Move source/test package directories to `com/ragbrain`.
- [x] Replace package/import references from `com.msfg.rag` to `com.ragbrain`.
- [x] Rename application and document classes.
- [x] Replace type references to `MortgageDocument` and `MortgageDocumentRepository`.
- [x] Update Gradle group to `com.ragbrain`.
- [x] Run `./gradlew compileTestJava`.

### Task 2: Dead-Code And Dependency Cleanup

**Files:**
- Modify: `build.gradle.kts` only if dependency usage proves a dependency is unused.
- Modify: stale tests/classes only when references prove they are dead after the generic rename.

**Interfaces:**
- Produces: no behavior change; smaller stale-name/dead-code surface.

- [x] Search for stale `msfg`, `MSFG`, `MortgageDocument`, `MsfgRagApplication`, and unused Java types.
- [x] Keep sample-pack mortgage strings where they intentionally describe example data.
- [x] Remove or rename only code with no active references.
- [x] Run `./gradlew test --tests '*RepositoryTest' --tests '*ServiceTest'` if backend code changed.

### Task 3: Advanced Agentic Retrieval Loop

**Files:**
- Modify: `src/main/java/com/ragbrain/rag/service/retrieval/AgenticRetrievalService.java`
- Modify: `src/main/java/com/ragbrain/rag/service/AskService.java`
- Test: `src/test/java/com/ragbrain/rag/service/retrieval/AgenticRetrievalServiceTest.java`
- Test: `src/test/java/com/ragbrain/rag/service/AskServiceTest.java`

**Interfaces:**
- Consumes: `RetrievalService.retrieve(String question, UUID brainId, SourceVisibility visibility)`.
- Produces: `AgenticRetrievalResult.confidenceReason()` containing `retrieval_attempts`, `selected_query`, `retrieval_confidence`, `source_count`, `distinct_document_count`, and `strategy`.

- [x] Add failing tests for gap-fill retrieval when initial selected evidence has low source diversity.
- [x] Implement bounded gap-fill in `AgenticRetrievalService`.
- [x] Ensure gap-fill never runs for insufficient rewrite retry failures.
- [x] Preserve existing AskService public response behavior.
- [x] Run focused AskService and AgenticRetrievalService tests.

### Task 4: Ingestion Quality Evaluation

**Files:**
- Create: `src/main/java/com/ragbrain/rag/service/ingestion/IngestionQualityService.java`
- Create: `src/main/java/com/ragbrain/rag/dto/IngestionQualityDto.java`
- Create: `src/main/java/com/ragbrain/rag/controller/IngestionQualityController.java`
- Modify: `src/main/java/com/ragbrain/rag/repository/BrainDocumentRepository.java`
- Modify: `src/main/java/com/ragbrain/rag/repository/DocumentChunkRepository.java`
- Test: `src/test/java/com/ragbrain/rag/service/ingestion/IngestionQualityServiceTest.java`
- Test: `src/test/java/com/ragbrain/rag/controller/IngestionQualityControllerTest.java`
- Modify dashboard if time permits: add quality view to Corpus or a small admin panel.

**Interfaces:**
- Produces: `GET /api/ai/admin/ingestion-quality?brain=<slug>` returning document/chunk/embedding/hierarchy/citation quality metrics.

- [x] Write failing service test for documents with missing chunks, missing embeddings, missing metadata, and duplicate content.
- [x] Implement repository queries/projections needed for quality metrics.
- [x] Implement service and controller.
- [x] Add minimal dashboard display or document API in README if dashboard changes are not justified.
- [x] Run focused ingestion quality tests.

### Task 5: Verification And Publish

**Files:**
- Modify docs only if setup commands changed.

- [x] Run `./gradlew test --rerun-tasks`.
- [x] Run `npm run check`.
- [x] Run `npm test -- --run`.
- [x] Run `npm run build`.
- [x] Review `git diff --stat`.
- [x] Commit and push branch.

