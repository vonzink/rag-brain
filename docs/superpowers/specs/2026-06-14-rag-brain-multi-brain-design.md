# rag-brain — Co-Resident Multi-Brain (brain_id) + Tiered Secure Design

- **Date:** 2026-06-14 (revised after design verification)
- **Status:** Approved (design) — pending implementation plan
- **Source project (leave untouched):** `/Users/zacharyzink/MSFG/msfg-rag`
- **Target project (all work happens here):** `/Users/zacharyzink/rag-brain`
- **Supersedes:** the earlier draft of this file (full-multi-tenant first pass) and the briefly-considered "schema-per-brain / active-brain reload" model. See §5 for why.
- **Builds on:** `docs/superpowers/specs/2026-06-10-rag-brain-platform-design.md` (the already-implemented single-brain-per-deployment platform).

> This design was validated by a parallel verification + adversarial audit of the cloned source (7 readers + 3 risk auditors). Key codebase facts are cited inline; the audit's load-bearing findings are folded into §5, §6, and §12.

## 1. Summary

`rag-brain` already is a working, company-agnostic RAG **platform** (the 2026-06-10 design: domain packs, runtime settings, model routing, in-app S3 sync, ops dashboard) — but single-brain-per-deployment. This spec adds **co-resident multi-brain** to one running instance: a registry of brains, each bound to its own knowledge source (S3 bucket/prefix or local folder), its own pack, and its own answer/utility model, isolated by a **`brain_id`** column. A request resolves to a brain once at entry (a default brain, overridable per request via `?brain=`), and the existing pipeline runs scoped to that brain. Genuinely **secure / air-gapped** brains are **not** co-resident — they run as a **separate deployment** (already supported), keeping a real isolation boundary. Everything is created and switched from the dashboard.

## 2. Background — what is already built (verified)

The cloned backend (Java 21, Spring Boot 3.5, Spring AI on classpath, PostgreSQL 16 + pgvector, Flyway, Vite/React dashboard) already implements the 2026-06-10 platform. Migrations run to **V6**:

- `V1__init_schema.sql` … `V3__rename_to_brain_tables.sql` → live tables are **`brain_documents`** and **`brain_document_chunks`** (not `mortgage_*`).
- `V4__create_brain_settings.sql` → global `brain_settings` key/value table + `RuntimeSettings` (cached, env-fallback) + `ModelRouterService` resolving **ANSWER** vs **UTILITY** provider/model.
- `V5__add_content_sha256.sql`, `V6__create_brain_rule_revisions.sql`.
- `DomainPack` + `DomainPackLoader` + `packs/msfg-mortgage/` (5 YAMLs), `service/sync/*` (`SyncService`, `SyncPlanner`, `SyncManifest`), dashboard screens (Corpus, Settings, TestConsole, Audit, Rules).

**Verified facts that shape this design:**
- All DB access is **schema-agnostic** and uses unqualified table names (entities are `@Table(name=…)` with no `schema=`; the only native SQL is the two hybrid queries in `DocumentChunkRepository`). New work = add a `brain_id` predicate, not rewrite access.
- **Spring AI's `VectorStore`/`PgVectorStore` is never instantiated** — retrieval is hand-written native SQL in `DocumentChunkRepository`. There is no vector-store bean to make brain-aware.
- `AuditLogService.record` runs `@Transactional(REQUIRES_NEW)` (`AuditLogService.java:31`) — a **separate pooled connection**. (This is why connection/`search_path` tricks fail and why brain context must be a request-scoped value, not a connection setting — §5, §8.)
- `AdminApiKeyFilter.shouldNotFilter` (`AdminApiKeyFilter.java:44`) gates **only** the `/api/ai/documents` and `/api/ai/admin` prefixes. Endpoints outside those are unauthenticated (§12).
- `DomainPack` is loaded once at boot as an immutable bean injected into ~8 singletons, some of which **precompute derived state** at construction (`QuestionClassifierService` compiled regex, `RetrievalService` compiled program patterns). Multi-brain requires a pack **registry** (§8).
- `RuntimeSettings` is a process-wide cache with parameterless accessors; `ModelRouterService` is **not** brain-aware. Per-brain models are **not** "free" — they require explicit brain-keyed columns + a brain-aware router (§8).

## 3. Relationship to the 2026-06-10 platform design

- **Reuse (unchanged foundation):** pack system + loader, `brain_*` tables, `brain_settings`/`RuntimeSettings`, `ModelRouterService` (ANSWER/UTILITY), `SyncService`/ingestion, dashboard, admin-key auth.
- **Supersede:** its duplication model ("copy the whole deployment per company") is replaced — for the *convenience* tier — by co-resident brains in one instance. The per-deployment model is **retained** as the **secure** tier (§5).
- **Newly in-scope (vs. that doc's §13 out-of-scope):** dashboard-managed brain lifecycle, a default brain + **per-request `?brain=` override**, per-brain model selection. We do **not** adopt per-user auth/ACLs or mutually-distrusting multi-tenancy.

## 4. Goals and non-goals

### Goals
- Many brains co-resident in one instance, each with its own source, pack, and answer/utility model.
- Per-request brain resolution (a dashboard-set default, overridable via `?brain=`), bound once per request.
- Documents isolated per brain via `brain_id`; retrieval never crosses brains.
- Per-brain model control + first-class **local/home-server LLM** support (OpenAI-compatible), with a per-brain endpoint override.
- Dashboard-managed brain lifecycle (create / configure / set-default / sync), with YAML export/import for reproducibility.
- A real isolation path for secure brains (separate deployment), documented, requiring no new code.

### Non-goals (v1 — YAGNI)
- **Schema-per-brain or per-brain databases** (see §5).
- Per-brain **embedding** models / per-brain embedding spaces (one shared `VECTOR(1536)` space; co-resident brains are filtered by `brain_id`).
- Per-user / SSO authorization or per-brain ACLs (the single `ADMIN_API_KEY` governs all co-resident brains — §12).
- Hosting mutually-distrusting tenants co-resident (that is the separate-deployment tier).
- Auto-sync / filesystem watching / scheduled ingestion (manual "Sync now").

## 5. Trust domain & the tiered model (read this first)

**All co-resident brains share ONE trust domain:** one process, one datasource, one DB role, one `ADMIN_API_KEY`, one set of provider keys. (A brain's optional local-LLM key is an operational override *within* this same trust domain, not a separate trust boundary.) Isolation between co-resident brains is a **correctness** property (a `brain_id` predicate), not a security boundary — a routing bug or a leaked admin key exposes every co-resident brain.

Therefore:
- **Convenience tier (this spec's new work):** co-resident brains via `brain_id`. For multi-purpose / single-purpose / low-stakes contexts you switch easily from the dashboard.
- **Secure tier (no new code):** a genuinely secure or air-gapped brain runs as a **separate deployment** — separate process + database + DB role + admin credential + (optionally) a local LLM with **no cloud fallback** — using the already-built per-deployment platform. Optionally registered in the dashboard later as an external link.

**Why `brain_id` and not schema-per-brain** (verified by the audit, high confidence): schema-per-brain in this codebase is fragile and buys complexity without the security payoff —
1. `AuditLogService` uses `REQUIRES_NEW` (separate pooled connection), so a per-request `search_path` would not cover the compliance audit insert → audit rows land in the wrong schema.
2. Hikari pooling leaks `search_path` to the next borrower → silent cross-brain reads, unless you build per-brain DataSources/`AbstractRoutingDataSource`.
3. Per-schema Flyway + pgvector extension visibility require a real migration redesign (N history tables, extension/`search_path` management).
4. Under one DB role, schemas are "isolation theater" anyway. Real isolation needs per-brain roles — and even then is not equivalent to a separate deployment.

`brain_id` avoids all four: isolation is a bound query parameter on the two native queries plus ingestion, resolved once per request, and it is honest about the trust domain.

## 6. Data model

A new `brains` registry table plus a `brain_id` foreign key on the per-brain data and the compliance trail.

**`brains`** (in the existing default schema): `id`, `slug` (unique, URL-safe; used in `?brain=` and `/api/ai/{slug}/ask`), `display_name`, `pack_ref` (path to a pack bundle, e.g. `packs/msfg-mortgage`), `source_type` (`s3` | `local`), source binding (`s3_bucket`/`s3_prefix`/`s3_region` **or** `local_path`), per-brain model (`answer_provider`, `answer_model`, `utility_provider`, `utility_model`), optional local-LLM override (`local_base_url`, `local_api_key_ref`), `is_default`, `is_active`, `created_at`, `updated_at`. (Secret columns: see §12 — store a reference/encrypted value, never plaintext returned by the API.)

**Constraints & lifecycle:** a **partial unique index** enforces at most one `is_default = TRUE` (in `V7`); setting a new default is a compare-and-set update. `slug` is DB-unique — a create with an existing slug returns `409`, while YAML import upserts by slug. Deleting a brain is a **soft-delete** (`is_active = false`) in v1; its documents/chunks are purged only by an explicit admin purge, and the compliance rows (`ai_audit_logs` etc.) are **never** cascade-deleted — their `brain_id` is retained and the FK to `brains` is `ON DELETE RESTRICT`, so the audit trail survives. **S3 access uses the instance's single AWS credential / IAM role for all brains** (the `brains` row stores only bucket/prefix/region — no per-brain S3 secret in v1); the only per-brain secret is the optional local-LLM key.

**`brain_id` FK added to:** `brain_documents`, `brain_document_chunks` (isolation for retrieval/ingestion) **and** `ai_audit_logs`, `ai_conversations`, `ai_messages`, `ai_answer_sources` (so the compliance trail is self-describing and immune to connection routing).

**Migration `V7`** (next after V6): create `brains`; add nullable `brain_id` columns; a boot-time `DefaultBrainSeeder` creates the **default brain** from current env (`BRAIN_SLUG`/`BRAIN_PACK`, source from `S3_*`/`DOCUMENT_STORAGE_PATH`, model from `DEFAULT_AI_*`) and **backfills** all existing rows to it; then `brain_id` is enforced `NOT NULL`. After upgrade, a single-brain instance behaves exactly as today.

**Embedding space:** one shared `VECTOR(1536)` column and HNSW index, filtered by `brain_id`. Per-brain embedding models are a non-goal (would need separate columns/indexes).

**Pack safety preserved per brain:** a brain's `slug` must match its pack's `pack.yaml` slug, or the brain is rejected — the existing guard, applied per brain.

## 7. Brain definition fields

Captured in the `brains` row (§6). The per-brain model columns and the optional `local_base_url`/`local_api_key_ref` are what let one instance run Claude for one brain and a LAN Ollama for another.

## 8. Brain resolution, pack registry, and model routing

**Resolution (request-scoped, bound once):** a request resolves its brain at entry — explicit `brain` request param (or `/api/ai/{slug}/ask` path) → else the default brain (`is_default`). The resolved brain id is placed in a **request-scoped / thread-bound context** and **never re-resolved mid-request**, so retrieval, persistence, and the `REQUIRES_NEW` audit insert all carry the same `brain_id` even if the default changes concurrently.

**Carrier mechanism (load-bearing):** the resolved `brain_id` is threaded **explicitly as a method parameter** through retrieval, persistence, and into `AuditLogService.record(...)` (which gains a `brain_id` argument), backed by a `@RequestScope BrainContext` bean for entry-time resolution. Explicit threading is required because the audit write runs `@Transactional(REQUIRES_NEW)` on a **separate connection/transaction** — a bare `ThreadLocal` / `TransactionSynchronizationManager` resource is not guaranteed to survive that boundary — and non-web callers (sync, §9) have no request scope at all.

**Pack registry (the core refactor):** replace the single immutable `DomainPack` bean with a `DomainPackRegistry` that loads + validates each registered brain's pack and caches its **per-brain derived state** (compiled classifier regex, compiled retrieval programs) — i.e. `QuestionClassifierService` and `RetrievalService` precompute **per brain**, not once at construction. Consumers resolve the current brain's pack from the request-scoped context. New/edited brains load+validate their pack (fail-fast) into the registry.

**Retrieval & ingestion scoping:** add `AND brain_id = :brainId` to both native queries in `DocumentChunkRepository` (`searchByVector`, `searchByKeyword`); ingestion writes (`DocumentIngestionService`, manual upload, and `SyncService`) set `brain_id`. The metadata filters (active, effective/expiration date) are unchanged.

**Model routing (brain-aware):** `ModelRouterService` resolves per request: brain's `answer_provider`/`answer_model` (and `utility_*`) → global `RuntimeSettings`/env default → fallback provider on error. The global runtime-settings row remains the default. Audit already records provider/model per answer.

## 9. Ingestion (per-brain)

Reuse the existing ingestion (Tika → chunk → embed → pgvector) and `SyncService`, parameterized by the brain:
- **S3** (`source_type=s3`): `SyncService` uses the brain's bucket/prefix/region (instead of global env); chunks tagged with `brain_id`.
- **Local folder** (`source_type=local`): ingest supported files under `local_path`; "Sync now" re-scans (idempotent by filename/content hash — `V5` sha256 already exists).
- Dashboard "Sync now" returns the existing plan/diff, scoped to the brain. Auto-sync remains a non-goal.

**Extension surface:** `SyncService.sync(...)` gains a `Brain` parameter (its source binding + `brain_id`); the `CorpusSource` is resolved **per brain** by `source_type` (S3 vs local) instead of a global singleton; because sync runs outside a web request, the brain is passed **explicitly** (not via request scope — §8).

## 10. Local-LLM provider stub (home servers)

Home-server LLMs (Ollama, LM Studio, vLLM, llama.cpp server) expose OpenAI-compatible endpoints, handled by the existing `OpenAiCompatibleProvider`.
- **Global stub (env):** `LOCAL_LLM_BASE_URL`, `LOCAL_LLM_API_KEY` (optional), `LOCAL_LLM_MODEL`; a key/URL-conditional bean in `ExtraProvidersConfig` (matching the existing pattern); appears in the dashboard provider list with a "configured" chip + editable base-URL. The stub registers the **local provider** (selectable per brain) with `LOCAL_LLM_MODEL` as a default; each brain picks its own model (and optional endpoint) on top.
- **Per-brain override:** a brain may set `local_base_url` (+ key ref) to target a specific home server; else it uses the global stub. **SSRF control (v1, not deferred):** validate `local_base_url` at write **and** call time against an allowlist (scheme `http`/`https`, host allowlisted), and block loopback, link-local, metadata, and private ranges by default (`127.0.0.0/8`, `169.254.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`). A private/LAN host is reachable **only** if explicitly listed in a `LOCAL_LLM_ALLOWED_HOSTS` allowlist validated at startup — there is no implicit carve-out.

## 11. Dashboard

- **Brains** screen (new): list/create/configure/set-default/sync/deactivate brains; per-brain model picker (only configured providers selectable) + local-endpoint field. **All Brains/activate/sync endpoints mount under `/api/ai/admin` (or `/api/ai/documents`)** so `AdminApiKeyFilter` actually gates them (§12).
- Existing **Corpus / Settings / TestConsole / Audit / Rules** screens gain a brain selector and operate on the selected brain.
- **YAML export/import** of a brain's *config* (not documents, not secrets) to `brains/<slug>.yaml` for git-tracked reproducibility; import creates/updates the brain and prompts for secrets. The YAML carries config fields only (incl. `pack_ref` as a repo-relative path resolved against the instance's `packs/` on import); secrets are excluded; import **upserts by `slug`**.

## 12. Security (verified findings — in scope for v1)

- **Trust domain statement (§5)** is part of the contract: co-resident = one trust domain; secure = separate deployment.
- **Admin auth gating:** every new brain-management endpoint must live under `/api/ai/admin` or `/api/ai/documents` (the only prefixes `AdminApiKeyFilter` covers). Add a test asserting `401` for an unauthenticated call to each new admin endpoint.
- **SSRF:** `local_base_url` allowlist at write + call time (§10).
- **Per-brain secrets** (`local_api_key`, S3 creds): never plaintext DB columns and never returned by the API. Encrypt at rest (pgcrypto) **or** store only a reference and keep the secret in AWS Secrets Manager (the `.env` header already states prod secrets live there). A **single redaction chokepoint** covers audit logging, exception messages, and YAML export; a test greps `ai_audit_logs` rows and exported YAML for known secret values and fails if found.
- **Compliance trail:** `brain_id` on `ai_audit_logs`/`ai_conversations`/`ai_messages`/`ai_answer_sources` (§6).
- **Air-gap honesty:** a brain flagged air-gapped (secure tier) must hard-error rather than silently fall back to a cloud provider, and requires a local embedding/answer model — enforced in the separate deployment, documented here.
- **Operational note:** the live-looking API keys and `ADMIN_API_KEY` currently in `rag-brain/.env` (carried from msfg-rag, gitignored, never committed) should be rotated before this ships, and a CI secret-scan gate added now that brain configs broaden secret storage.

## 13. Testing strategy

- **Migration + backfill:** existing documents/chunks/audit rows land in the seeded default brain; single-brain behavior unchanged after `V7`.
- **Retrieval isolation:** brain A never returns brain B's chunks (both native queries filter `brain_id`).
- **Request-scoped binding:** a request resolved to brain X writes its `REQUIRES_NEW` audit row with `brain_id = X` (guards the separate-connection trap).
- **Resolution:** explicit `?brain=` / path override vs default fallback.
- **Model routing precedence:** brain → global default → fallback, incl. the local provider + per-brain endpoint override.
- **SSRF:** disallowed `local_base_url` rejected at write and call time.
- **Admin auth:** unauthenticated call to each new admin endpoint returns `401`.
- **Secrets:** redaction-chokepoint test (audit + YAML export contain no plaintext secret); YAML config round-trip.
- **Ingestion:** create brain + local-folder ingest; S3 sync parameterized by brain; chunks carry `brain_id`.

## 14. Build order (phases)

Each phase keeps the app working (the default brain behaves as today until later phases add UI).

1. **Phase 1 — Clone & verify** (DONE): clean-template clone; build/test/boot/dashboard parity.
2. **Phase 2 — Data model:** `brains` table, `DefaultBrainSeeder` + backfill. `V7` adds `brain_id` to **all six tables** (data + compliance) as **nullable**, backfills every existing row to the default brain, then sets `NOT NULL` + the `is_default` partial-unique index. Existing behavior intact; columns exist for later phases to populate.
3. **Phase 3 — Brain context + pack registry + scoped retrieval:** request-scoped brain resolution (default + `?brain=`) + the explicit `brain_id` carrier into `AuditLogService.record`, `DomainPackRegistry` with per-brain derived-state caching, `brain_id` predicate on retrieval, `brain_id` on persistence/audit writes. The core refactor (may be split into sub-plans; the `brain_id` columns already exist from Phase 2).
4. **Phase 4 — Brain-aware model routing + local-LLM:** per-brain model columns honored by `ModelRouterService`; `LOCAL_LLM_*` provider + per-brain endpoint override + SSRF allowlist.
5. **Phase 5 — Per-brain ingestion:** source binding on the brain; upload + `SyncService` set `brain_id`; "Sync now" scoped per brain.
6. **Phase 6 — Dashboard + security hardening:** Brains screen + selector + YAML export/import; admin-prefix mounting + 401 tests; secret encryption/Secrets-Manager + redaction chokepoint.
7. **Phase 7 — Tests + docs:** §13 coverage; update `README`/`RUNBOOK`/`.env.example`; document the trust-domain/secure-tier model.

## 15. Future extensions (out of scope)

Per-brain embedding spaces; scheduled/auto-sync; per-user authorization and brain ACLs; registering external (separate-deployment) secure brains in the dashboard; host-allowlist management UI; promoting a co-resident brain to a separate deployment.
