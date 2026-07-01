# Generic Agentic Cleanup Design

## Goal

Finish the next cleanup pass for rag-brain by removing provably stale code, renaming the backend away from MSFG-specific package/domain names, expanding the agentic retrieval loop beyond one rewrite retry, and adding ingestion quality evaluation.

## Scope

- Keep mortgage content as examples only: `packs/msfg-mortgage`, example documents, and tests that intentionally exercise the sample pack.
- Rename Java package root from `com.msfg.rag` to `com.ragbrain`.
- Rename document domain types from `MortgageDocument` to `BrainDocument` while preserving existing database tables and Flyway history.
- Do not rename database tables again. The live tables are already generic: `brain_documents` and `brain_document_chunks`.
- Do not introduce new external runtime dependencies unless the code proves an existing dependency is unused and safe to remove.

## Architecture

The platform package becomes `com.ragbrain`, with `RagBrainApplication` as the boot class. Document model names align with the current generic table names. The RAG answer flow keeps `AskService` as the public orchestrator for now, but retrieval intelligence moves into `AgenticRetrievalService` and supporting value types so future agentic work does not keep inflating `AskService`.

Agentic retrieval will add a bounded multi-step plan:

1. Initial retrieval with the raw user query.
2. Vocabulary rewrite retry when initial evidence is weak.
3. Gap-fill retrieval when the selected evidence is sufficient but has too few distinct source documents or low citation diversity.
4. Side-evidence collection against the selected query.
5. Structured trace metadata for each attempt.

Ingestion quality evaluation will be read-only. It will inspect current documents/chunks and report coverage problems: missing chunks, missing embeddings, empty content, weak hierarchy coverage, duplicate chunk text, and incomplete citation metadata.

## Testing

- Package/domain rename is verified by full backend compile/tests.
- Agentic retrieval changes are covered with focused unit tests around retry/gap-fill behavior and AskService integration.
- Ingestion quality evaluation is covered with service/controller tests using mock repositories.
- Dashboard changes are verified with `npm run check`, `npm test -- --run`, and `npm run build`.

