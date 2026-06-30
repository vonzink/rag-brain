# Deploying rag-brain (container)

The repo ships a multi-stage `Dockerfile` that builds the Spring Boot jar and runs
it as a non-root, health-checked container. The image defaults to the **`prod`
profile**, which is secure-by-default: startup aborts if the admin key, database
password, or CORS origins are still local development defaults.

## Required configuration

| Env var | Required | Notes |
|--------|----------|-------|
| `ADMIN_API_KEY` | **Yes** | Boot aborts under `prod` if blank or the dev default. Use a strong, unique value. |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | **Yes** | Point at your Postgres (with the `pgvector` extension). Boot aborts if `DB_PASSWORD` is blank or the dev default. |
| `CORS_ALLOWED_ORIGINS` | **Yes** | Comma-separated admin/dashboard origins. Boot aborts if empty or localhost-only under `prod`. |
| `OPENAI_API_KEY` | For retrieval | Embeddings use OpenAI today; required for ingestion/answers. |
| `ANTHROPIC_API_KEY` | For answers | Default answer provider. Either provider key enables answering. |

The app boots **without** AI keys (admin/dashboard only) but cannot ingest or
answer until at least an embedding key is present. See `.env.example` for the
full list of tunables.

## Run local Postgres with docker compose

The default compose command starts only PostgreSQL + pgvector for local host-run
development:

```bash
docker compose up -d
```

## Run the app image with docker compose

The application container is opt-in so it does not conflict with local
`./gradlew bootRun` on port `8091`:

```bash
ADMIN_API_KEY='choose-a-strong-key' \
DB_PASSWORD='choose-a-db-password' \
CORS_ALLOWED_ORIGINS='https://dashboard.example.com' \
OPENAI_API_KEY='sk-...' \
ANTHROPIC_API_KEY='sk-ant-...' \
docker compose --profile app up --build app
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
  -e RATE_LIMIT_REQUESTS_PER_MINUTE='10' \
  -e CONNECTOR_RATE_LIMIT_REQUESTS_PER_MINUTE='60' \
  -e ADMIN_RATE_LIMIT_REQUESTS_PER_MINUTE='120' \
  -e RATE_LIMIT_TRUST_FORWARDED_FOR='true' \
  -e RATE_LIMIT_TRUSTED_PROXY_COUNT='1' \
  -e OPENAI_API_KEY='sk-...' \
  -e ANTHROPIC_API_KEY='sk-ant-...' \
  -v rag_brain_docdata:/app/data/documents \
  rag-brain:latest
```

## Recommended production defaults

| Area | Default |
|------|---------|
| Public ask rate limit | `RATE_LIMIT_REQUESTS_PER_MINUTE=10` per client IP per minute |
| Connector/MCP rate limit | `CONNECTOR_RATE_LIMIT_REQUESTS_PER_MINUTE=60` per client IP per minute |
| Admin rate limit | `ADMIN_RATE_LIMIT_REQUESTS_PER_MINUTE=120` per client IP per minute |
| Trusted proxy handling | `RATE_LIMIT_TRUST_FORWARDED_FOR=true` only when all traffic arrives through the proxy |
| Logs | `com.msfg.rag=INFO`, `org.springframework=WARN`; do not log raw tokens or prompts |
| Alerting | alert on app down, DB down, repeated `5xx`, repeated `401/403`, high `429`, disk usage over 80%, failed backups |
| Backups | automated daily DB backups with point-in-time recovery; keep at least 14 days before launch |
| Token rotation | rotate admin, public, and connector tokens every 120 days; rotate immediately on staff/vendor change or suspected exposure |

## Postgres and pgvector

Use a managed PostgreSQL instance where possible. The database must support the
`vector` extension and the app role must be allowed to run Flyway migrations.
The first migration runs `CREATE EXTENSION IF NOT EXISTS vector;`, creates the
`VECTOR(1536)` embedding column, and adds an HNSW cosine index for retrieval.

Minimum production shape:

- PostgreSQL 16 compatible engine with `pgvector`.
- Private subnet/security group: only the app can connect to port `5432`.
- Dedicated DB user for the app, not a shared admin user.
- Automated backups and point-in-time recovery enabled.
- Storage autoscaling or alerting before disk fills.
- Run migrations during app startup or as a separate deploy step, but only once
  per deployment.

## TLS, domain, and proxy

The Spring app speaks HTTP. Terminate TLS at a load balancer, reverse proxy, or
ingress, then forward to the app container on port `8080`.

Suggested routing:

- `https://brain.example.com` -> API container `:8080`
- `https://dashboard.example.com` -> built dashboard/static host or Vite dev only for local use
- Public websites embed `https://brain.example.com/widget/rag-brain-widget.js`

Proxy requirements:

- Forward `Host`, `X-Forwarded-Proto`, and `X-Forwarded-For`.
- Set `RATE_LIMIT_TRUST_FORWARDED_FOR=true` only when direct internet traffic
  cannot bypass the proxy.
- Set `RATE_LIMIT_TRUSTED_PROXY_COUNT=1` for one proxy hop, or `2` for ALB plus
  Nginx/ingress.
- Enforce HTTPS redirect and modern TLS at the proxy/load balancer.

## CORS

`CORS_ALLOWED_ORIGINS` is the static allowlist for admin/dashboard origins and
any known fixed public origins. Public website assistant calls also support
dynamic per-brain domains managed in the dashboard Connect flow.

Production rules:

- Use full origins, not hosts: `https://dashboard.example.com,https://www.example.com`.
- Do not use `*`.
- Do not keep localhost in production.
- Add each public website domain in the brain's Connect/Personality settings so
  public widget calls pass dynamic CORS and server-side origin validation.

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
