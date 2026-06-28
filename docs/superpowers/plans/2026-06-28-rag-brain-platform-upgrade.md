# RAG Brain Platform Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the useful `msfg-rag` dashboard/control-plane features into `rag-brain`, then extend the target with PostgreSQL/pgvector hierarchical chunking and agentic RAG traces.

**Architecture:** Keep `rag-brain` as the base because it already contains the multi-brain registry, brain-scoped retrieval, local/S3 corpus source binding, and local LLM routing. Port source-only features as brain-scoped additions, not global tables. Add hierarchical parent chunks without disrupting existing child chunk retrieval.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, PostgreSQL 16, pgvector with HNSW cosine index, React/Vite/TypeScript dashboard.

---

### Task 1: Control Plane Merge

**Files:**
- Create: `src/main/java/com/msfg/rag/domain/BrainSourceLink.java`
- Create: `src/main/java/com/msfg/rag/domain/BrainPageGuide.java`
- Create: `src/main/java/com/msfg/rag/domain/VocabularyRevision.java`
- Create: `src/main/java/com/msfg/rag/controller/AdminSourceLinkController.java`
- Create: `src/main/java/com/msfg/rag/controller/AdminPageGuideController.java`
- Create: `src/main/java/com/msfg/rag/controller/AdminVocabularyController.java`
- Modify: `src/main/java/com/msfg/rag/pack/DomainPack.java`
- Modify: `src/main/java/com/msfg/rag/pack/DomainPackLoader.java`

- [x] Copy additive registry/vocabulary files from `msfg-rag`.
- [x] Add `brain_id` to copied entities and repositories.
- [x] Route admin controllers through `BrainResolver`.
- [x] Add first-boot pack seeders for optional source-link/page-guide YAML.

### Task 2: Hierarchical Chunks

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ingestion/TextChunk.java`
- Modify: `src/main/java/com/msfg/rag/service/ingestion/ChunkingService.java`
- Modify: `src/main/java/com/msfg/rag/service/ingestion/DocumentIngestionService.java`
- Modify: `src/main/java/com/msfg/rag/domain/DocumentChunk.java`
- Modify: `src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java`
- Create: `src/main/resources/db/migration/V10__add_hierarchical_chunks.sql`

- [x] Preserve `chunk()` for existing callers/tests.
- [x] Add `chunkHierarchical()` to produce parent section chunks plus child retrieval chunks.
- [x] Store parent chunks without embeddings and child chunks with embeddings.
- [x] Restrict vector/keyword retrieval to child chunks while selecting parent context.

### Task 3: Agentic RAG Trace

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Create: `src/main/java/com/msfg/rag/domain/RagTrace.java`
- Create: `src/main/java/com/msfg/rag/repository/RagTraceRepository.java`
- Create: `src/main/java/com/msfg/rag/service/audit/RagTraceService.java`
- Create: `src/main/resources/db/migration/V11__create_rag_traces.sql`

- [x] Add query rewrite preview through live vocabulary.
- [x] Add intent routing and retrieval planning before retrieval.
- [x] Collect side evidence from source links/page guides.
- [x] Return deterministic recommended page, links, next action, and trace id.
- [x] Persist trace JSON for retrieval decisions and answer citations.

### Task 4: Dashboard

**Files:**
- Modify: `dashboard/src/App.tsx`
- Modify: `dashboard/src/api.ts`
- Modify: `dashboard/src/types.ts`
- Modify: `dashboard/src/screens/Corpus.tsx`
- Create: `dashboard/src/screens/Vocabulary.tsx`
- Create: `dashboard/src/screens/SourceLinks.tsx`
- Create: `dashboard/src/screens/PageGuides.tsx`
- Modify: `dashboard/src/screens/TestConsole.tsx`

- [x] Add missing control-plane screens to navigation.
- [x] Add document upload/edit/delete workflow.
- [x] Display response links/page/trace in the test console.
- [x] Run dashboard typecheck/build and fix TypeScript issues.

### Task 5: Verification And Docs

**Files:**
- Modify: `.env.example`
- Modify: `README.md`
- Create: `src/main/resources/db/migration/V9__create_control_plane_tables.sql`

- [x] Add migrations for control-plane tables.
- [x] Update reusable platform docs and env examples.
- [x] Run Java tests.
- [x] Run dashboard checks.
