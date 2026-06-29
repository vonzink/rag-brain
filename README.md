# rag-brain

Reusable RAG brain platform with a Spring Boot API, React admin dashboard, and PostgreSQL + pgvector vector store.

The default brain is intentionally generic and starts with no business-specific corpus. The bundled `packs/msfg-mortgage` pack and copied mortgage documents are examples for testing only. New brains can use generated starter packs or their own pack directory without changing backend code.

## Stack

Java 21, Spring Boot 3.5, Spring AI 1.1, PostgreSQL 16, pgvector, Flyway, Gradle, React/Vite/TypeScript.

## Local Start

Prereqs: JDK 21, Docker Desktop, Node 20+.

```bash
cp .env.example .env
# optional for dashboard-only boot:
# fill OPENAI_API_KEY for embeddings/retrieval, and an answer provider key such as ANTHROPIC_API_KEY for generated answers

docker compose up -d
set -a && source .env && set +a
./gradlew bootRun --args="--server.port=8091"
```

Dashboard:

```bash
cd dashboard
npm install
npm run dev -- --port 5174
```

One-command local start is also available:

```bash
./start.sh
```

## Database And pgvector

`docker-compose.yml` runs `pgvector/pgvector:pg16` on host port `5435`.

Flyway owns schema creation. `V1__init_schema.sql` enables:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Document child chunks use `VECTOR(1536)` by default for `text-embedding-3-small`. The vector index is HNSW cosine:

```sql
CREATE INDEX idx_chunks_embedding ON brain_document_chunks
  USING hnsw (embedding vector_cosine_ops);
```

Run migrations by starting the app:

```bash
./gradlew bootRun
```

## Ingest Documents

Use the dashboard Corpus screen or the admin API:

```bash
curl -X POST http://localhost:8091/api/ai/documents/upload \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -F "file=@example.pdf" \
  -F "title=Example Source" \
  -F "sourceName=Example" \
  -F "sourceType=EDUCATIONAL"
```

Supported source types: `AGENCY_GUIDELINE`, `INTERNAL_POLICY`, `INVESTOR_OVERLAY`, `EDUCATIONAL`.

For a registered brain, pass `?brain=<slug>` to admin document endpoints.

## Query Flow

```bash
curl -X POST http://localhost:8091/api/ai/generic/ask \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "local-test",
    "question": "What does the uploaded source say about renewals?",
    "pageRoute": "/knowledge/base",
    "surface": "PUBLIC"
  }'
```

The response includes `answer`, `citations`, confidence/escalation flags, optional `recommendedPage`, `links`, `nextAction`, and `traceId`. Query and ingestion flows require `OPENAI_API_KEY` because embeddings are generated with OpenAI by default. The API and dashboard can still boot without AI keys so admin setup is possible first.

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
See [docs/public-assistant.md](docs/public-assistant.md) for the full request/response contract.

Pipeline:

```text
question
  -> safety classifier
  -> intent router
  -> vocabulary/query rewrite preview
  -> retrieval planner
  -> child-chunk vector + keyword retrieval
  -> parent-section context assembly
  -> answer model
  -> citation/guardrail validation
  -> audit log + RAG trace
```

## Dashboard Workflows

The dashboard supports:

- Brains: create, activate, sync local/S3 source bindings.
- Corpus: upload, edit metadata, delete, activate/deactivate, reindex, sync.
- Vocabulary: edit retrieval synonyms.
- Source Links: manage approved external citation/source links.
- Page Guides: manage recommended pages and internal links.
- Test Console: run full ask or retrieval-only tests.
- Audit: inspect answers and retrieved sources.

## Example Mortgage Pack

Mortgage/MSFG material is kept out of the generic default brain. To test with the example mortgage pack, create or activate a separate brain that uses:

```text
packs/msfg-mortgage
```

Copied mortgage files are local examples under:

```text
data/examples/mortgage-documents
```

Upload a curated subset through the dashboard Corpus screen rather than syncing all copied files blindly, because that folder contains duplicates.

## Tests

```bash
./gradlew test
cd dashboard && npm run build
```

Integration tests use Testcontainers and require Docker.
