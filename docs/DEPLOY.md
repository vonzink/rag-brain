# Deploying rag-brain (container)

The repo ships a multi-stage `Dockerfile` that builds the Spring Boot jar and runs
it as a non-root, health-checked container. The image defaults to the **`prod`
profile**, which is secure-by-default: startup aborts if the admin key is still a
dev default.

## Required configuration

| Env var | Required | Notes |
|--------|----------|-------|
| `ADMIN_API_KEY` | **Yes** | Boot aborts under `prod` if blank or the dev default. Use a strong, unique value. |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | **Yes** | Point at your Postgres (with the `pgvector` extension). A default DB password only warns. |
| `CORS_ALLOWED_ORIGINS` | Recommended | Comma-separated site origins. Localhost-only warns. |
| `OPENAI_API_KEY` | For retrieval | Embeddings use OpenAI today; required for ingestion/answers. |
| `ANTHROPIC_API_KEY` | For answers | Default answer provider. Either provider key enables answering. |

The app boots **without** AI keys (admin/dashboard only) but cannot ingest or
answer until at least an embedding key is present. See `.env.example` for the
full list of tunables.

## Run with docker compose (local prod-like)

Brings up Postgres + the app together:

```bash
ADMIN_API_KEY='choose-a-strong-key' \
OPENAI_API_KEY='sk-...' \
ANTHROPIC_API_KEY='sk-ant-...' \
docker compose up --build
```

App: `http://localhost:8091` (container `8080`). Postgres: host `5435`.

## Run the image standalone

```bash
docker build -t rag-brain:latest .

docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e ADMIN_API_KEY='choose-a-strong-key' \
  -e DB_URL='jdbc:postgresql://<host>:5432/rag_brain' \
  -e DB_USERNAME='rag_brain' \
  -e DB_PASSWORD='<managed-secret>' \
  -e CORS_ALLOWED_ORIGINS='https://yoursite.com' \
  -e OPENAI_API_KEY='sk-...' \
  -e ANTHROPIC_API_KEY='sk-ant-...' \
  -v rag_brain_docdata:/app/data/documents \
  rag-brain:latest
```

## Operational notes

- **Health probe:** `GET /actuator/health` (used by the container `HEALTHCHECK`
  and suitable for liveness/readiness). Under `prod`, actuator exposes only
  `health` with details hidden; `info`/`metrics` are not public.
- **Schema:** Flyway runs on startup (`ddl-auto: validate`); the database must be
  reachable and the `vector` extension available (the `pgvector/pgvector` image
  provides it; AWS RDS supports it natively).
- **Document storage:** local blobs live under `/app/data/documents` — mount a
  volume (compose does this) or switch to S3 corpus sync for durability.
- **Domain packs:** baked into the image at `/app/packs`; the default brain uses
  `packs/generic`.
- **TLS:** terminate TLS at your load balancer / ingress; the app speaks HTTP.
- **Heap:** `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` sizes the heap from the
  container memory limit — set a memory limit on the container.

## Scaling caveat

Rate limiting is in-memory per instance. Running multiple replicas multiplies the
effective public-ask limit and resets buckets per pod; move to a shared store
(e.g. Redis) before horizontal scaling matters.
