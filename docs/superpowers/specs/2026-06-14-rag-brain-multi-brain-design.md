# rag-brain — Multi-Brain Template Design

- **Date:** 2026-06-14
- **Status:** Approved (design) — pending implementation plan
- **Source project:** `/Users/zacharyzink/MSFG/msfg-rag`
- **Target project:** `/Users/zacharyzink/rag-brain`

## 1. Summary

`rag-brain` is a clean-template clone of `msfg-rag` extended so that **one running
instance can host many "brains."** Each brain is bound to its own knowledge source
(an S3 bucket+prefix or a local folder), its own pack (prompt, hard-rules,
classifier, guardrails, retrieval tuning), and its own answer/utility AI model. A
default brain is toggled live from the dashboard; any API call can override the brain
per request. The result is a reusable template for building **specialized,
guideline-bound AI assistants** for any project — secure or local, multi-purpose or
single-purpose — all created and switched from the dashboard.

## 2. Background — what msfg-rag already provides

msfg-rag is a Spring Boot (Java 21) + Spring AI RAG backend with a Vite/React
dashboard and PostgreSQL 16 + pgvector. It already supports guideline-bound answering
through the **pack system** (`packs/msfg-mortgage/`): `prompt.yaml` (template +
hard-rules + guidance), `classifier.yaml` (pre-retrieval question filtering),
`guardrails.yaml` (prohibited phrases + canned answers), `retrieval.yaml` (acronyms +
program-aware ranking), and `pack.yaml` (identity + disclaimer). It supports multiple
AI providers (Anthropic primary, OpenAI embeddings/fallback, plus OpenAI-dialect
adapters for DeepSeek/Gemini/Groq via `OpenAiCompatibleProvider`), local-or-S3
document storage, hybrid vector+keyword retrieval, and post-generation answer
validation with a full audit trail.

**The limitation:** all of this is single-brain. The knowledge source, pack, and
model are global (env + a single global runtime-settings row). Swapping context means
editing `.env`/pack files and restarting — one brain at a time.

## 3. Goals and non-goals

### Goals
- Host many brains in one instance, each with its own source, pack, and model.
- Switch the active/default brain live (no restart); override per request.
- Isolate each brain's documents so retrieval never crosses brains.
- Per-brain answer/utility model selection from configured providers.
- First-class support for **local/home-server LLMs** (OpenAI-compatible endpoints),
  including a per-brain endpoint override.
- Create, configure, ingest, and switch brains from the dashboard, with YAML
  export/import for version-controlled, reproducible setups.

### Non-goals (v1 — YAGNI)
- Per-brain **embedding** models (a single shared vector space is required; see §5).
- Per-user / SSO authorization on brain selection (the existing admin API key remains
  the gate for management; public ask endpoints stay open per current behavior).
- Auto-sync / filesystem watching / scheduled ingestion (manual "Sync now" only).
- Cross-brain "ask all brains at once" / federated queries.

## 4. Step 0 — the clone (clean template clone)

Performed as the first execution step, after the implementation plan is approved.

**Copy into `/Users/zacharyzink/rag-brain`:** all source (`src/`), `packs/`, build
config (`build.gradle.kts`, `settings.gradle.kts`, `gradlew*`, `gradle/`),
`docker-compose.yml`, `docs/`, `scripts/`, dashboard **source** (`dashboard/src`,
`dashboard/index.html`, `dashboard/package.json`, `dashboard/tsconfig.json`,
`dashboard/vite.config.ts`, `dashboard/.gitignore`), `data/documents`, and `.env`
(carried over so it runs immediately).

**Do not copy** (regenerable / noise): `build/`, `.gradle/`, `node_modules/`,
`dashboard/dist/`, `.DS_Store`, and msfg-rag's `.git/` history.

**Git:** `rag-brain` is a fresh repo (already initialized on `master` to hold this
spec). The clone lands as its own commit ("Clone source from msfg-rag"). The existing
`docs/superpowers/specs/` planning docs are preserved.

**Verification gate:** before any feature work, confirm the clone builds and boots —
`./gradlew build`, `./gradlew bootRun` against the docker-compose Postgres, and the
dashboard runs (`npm install && npm run dev`). Behavior must match msfg-rag exactly at
this point (single brain, existing pack).

## 5. Architecture — the brain dimension

### 5.1 Data model and isolation

**Chosen approach: a `brain_id` column on shared tables, filtered on every query.**
A new `brains` table holds brain definitions. `mortgage_documents` and
`mortgage_document_chunks` gain a `brain_id` foreign key. Every retrieval query
(vector, keyword, metadata-filtered) adds `WHERE brain_id = ?`. One pgvector/HNSW
index and one embedding space are retained; live switching is a parameter change, not
a re-provisioning.

Alternatives rejected: **schema-per-brain** (dynamic schema creation + per-brain
Spring AI vector-store wiring is heavy) and **database-per-brain** (strongest
isolation, heaviest ops, awkward live switching). The column approach is the natural
fit for "many brains, switch live in one instance."

**Migration & backfill.** A Flyway migration (`V2`) creates the `brains` table and
adds a nullable `brain_id` to documents and chunks. A boot-time `DefaultBrainSeeder`:
(1) if `brains` is empty, creates the **default brain** from current env values
(`BRAIN_SLUG`, `BRAIN_PACK`, source from `DOCUMENT_STORAGE_PATH`/`S3_*`, model from
`DEFAULT_AI_PROVIDER`/`DEFAULT_AI_MODEL`); (2) backfills every `brain_id IS NULL`
document/chunk row to that default brain. This preserves msfg-rag's current behavior
exactly after upgrade. Once seeding/backfill is reliable, `brain_id` is enforced
NOT NULL.

**Embedding constraint.** All brains share one embedding model and dimension (the
single `VECTOR(1536)` column). Per-brain control applies to the *answer/utility*
model only. A fully air-gapped instance can still set the **embedding** provider to a
local model — but globally (uniform across brains), documented as the secure/local
path.

### 5.2 What a "brain" is

A `brains` row:

| Field | Purpose |
|-------|---------|
| `slug` | Unique, URL-safe identifier (used in `?brain=<slug>`). |
| `display_name` | Human label for the dashboard. |
| `pack_ref` | Path to the pack bundle (e.g. `packs/msfg-mortgage`). |
| `source_type` | `s3` or `local`. |
| `s3_bucket`, `s3_prefix`, `s3_region` | S3 binding (when `source_type=s3`). |
| `local_path` | Folder path (when `source_type=local`). |
| `answer_provider`, `answer_model` | Chat model that writes answers. |
| `utility_provider`, `utility_model` | Cheaper model for rerank/classify (optional). |
| `local_base_url`, `local_api_key` | Optional per-brain local-LLM endpoint override. |
| `is_default` | The brain used when no `brain` param is supplied. |
| `is_active` | Whether the brain accepts queries / appears as selectable. |
| timestamps | `created_at`, `updated_at`. |

**Pack safety preserved per brain:** a brain's `slug` must match its pack's
`pack.yaml` slug, or the brain is rejected — the same guard msfg-rag uses today,
applied per brain.

## 6. Brain lifecycle and management

A new admin-key-protected **Brains** screen in the dashboard:

- **List** brains with source, pack, model, active/default status.
- **Create:** name → pick or clone a pack → choose source (S3 bucket+prefix+region, or
  local folder path) → pick answer/utility provider+model (only configured providers
  appear; reuse the existing "configured" chip logic) → optional local endpoint
  override.
- **Sync now:** ingest/refresh documents from the brain's source.
- **Set as default**, **activate/deactivate**, **delete** (delete removes the brain
  and its chunks).
- **YAML export/import:** a brain's *config* (not its documents) serializes to
  `brains/<slug>.yaml`, so a setup is git-trackable and reproducible on another
  instance. Import creates/updates the brain from the file.

Brains live in the DB (so they can be added live); YAML is the portable, declarative
mirror.

## 7. Ingestion (per-brain)

Generalize the two existing paths to be brain-scoped; chunks are tagged with the
brain's `brain_id`.

- **Local folder** (`source_type=local`): ingest every supported file (PDF, DOCX, TXT,
  Markdown, HTML via the existing Tika pipeline) under `local_path`. "Sync now"
  re-scans, idempotent by filename (matching the current S3 script behavior).
- **S3** (`source_type=s3`): the existing `scripts/s3-ingest/sync.mjs` and
  `S3CorpusSource` are parameterized by the brain's bucket/prefix/region instead of
  global env.
- Auto-watch / scheduled sync is a non-goal for v1.

## 8. Query flow and brain resolution

`AskController` resolves the brain: explicit `brain` request param → else the default
brain (`is_default`). The **existing** pipeline then runs scoped to that brain:

1. Classifier uses the brain's pack rules.
2. Retrieval expands with the brain's pack acronyms/programs and filters `brain_id`.
3. Prompt builder injects the brain's pack hard-rules + guidance.
4. Model router calls the brain's configured model (see §9).
5. Answer validation applies the brain's pack guardrails (prohibited phrases, canned
   answers).
6. Audit log records which brain answered, alongside the existing fields.

Guideline-control behavior is unchanged — it is simply multiplied per brain. The
public ask API and the embed snippet gain an optional `brain` parameter.

## 9. Model routing (per-brain)

`ModelRouterService` becomes brain-aware. Resolution order for the **answer** call:

1. Brain's `answer_provider` + `answer_model`.
2. Global default (`DEFAULT_AI_PROVIDER` / `DEFAULT_AI_MODEL`).
3. Fallback provider (`FALLBACK_AI_PROVIDER` / `FALLBACK_AI_MODEL`) on error.

The **utility** call resolves: brain's `utility_*` → brain's `answer_*` → global
utility/default. Only providers that are actually configured are selectable in the
dashboard. **Embeddings remain global** (§5.1).

The existing global runtime-settings row is retained as the source of the global
defaults; per-brain fields override it for that brain.

## 10. Local-LLM provider stub (home servers)

Home-server LLMs (Ollama, LM Studio, vLLM, llama.cpp server) expose
OpenAI-compatible endpoints, so the local LLM slots in through the existing
`OpenAiCompatibleProvider`.

- **Global stub (env):** `LOCAL_LLM_BASE_URL`, `LOCAL_LLM_API_KEY` (optional — many
  local servers ignore it), `LOCAL_LLM_MODEL`. The provider auto-enables when
  `LOCAL_LLM_BASE_URL` is set (matching the existing key-presence conditional in
  `ExtraProvidersConfig`) and appears in the dashboard provider list with a
  "configured" chip and an editable base-URL field.
- **Per-brain override:** a brain may set `local_base_url` + `local_api_key` (+ model)
  to target a specific home server. If unset, it uses the global `LOCAL_LLM_*` stub.
  This lets different brains hit different boxes (one brain → Claude cloud, another →
  `http://192.168.x.x:11434`), switchable live.

## 11. Configuration surface (global vs per-brain)

**Stays global:** provider API keys (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
`DEEPSEEK_API_KEY`, `GEMINI_API_KEY`, `GROK_API_KEY`), the new `LOCAL_LLM_*` slots,
DB connection, embedding model + dimensions, `ADMIN_API_KEY`, `CORS_ALLOWED_ORIGINS`,
global default/fallback model.

**Moves to per-brain (env values seed the default brain on first boot for
backward-compat):** `BRAIN_PACK`/`BRAIN_SLUG`, source binding
(`S3_BUCKET`/`S3_PREFIX`/`AWS_REGION` or `DOCUMENT_STORAGE_PATH`), answer/utility
model. Retrieval tuning is **already per-brain via the pack** (`retrieval.yaml`,
reached through `pack_ref`); the global runtime knobs (`RETRIEVAL_TOP_K`,
`RETRIEVAL_CONFIDENCE_THRESHOLD`, `RETRIEVAL_RERANK_ENABLED`) stay global defaults in
v1, with per-brain overrides listed as a future extension.

`.env.example` is updated to document the new `LOCAL_LLM_*` slots and to note that the
single-brain env vars now seed the default brain.

## 12. Dashboard changes

- **Brains** screen (new) — §6.
- **Settings/providers** screen — add the local provider with an editable base-URL
  field and "configured" chip.
- **Test console / Corpus / Audit / Rules** screens — scoped by a brain selector
  (which brain you are testing, uploading to, viewing logs/rules for).

## 13. Template use-cases this enables

- **Secure / air-gapped brain:** global local embeddings + per-brain local answer
  model + a strict pack (tight hard-rules, low confidence threshold) + a private
  local-folder corpus.
- **Multi-purpose brain:** cloud model, broad pack, larger corpus.
- **Single-purpose brain:** a narrow pack + one folder/bucket + whatever model fits.

All created and switched from the dashboard; all guideline-bound by their pack.

## 14. Security considerations

- Brain management (create/edit/sync/delete, YAML import) is gated by `ADMIN_API_KEY`,
  as document upload/settings are today.
- `local_base_url` and S3 bindings are admin-supplied; arbitrary outbound URLs are
  acceptable because only admins set them, but this should be noted (a future hardening
  could allowlist hosts).
- `local_api_key` and source credentials are stored in the DB; treat the `brains`
  table as secret-bearing (no logging of key fields; redact in audit and YAML export —
  export references the env/secret indirectly or omits the secret value, with import
  prompting for it).

## 15. Testing strategy

- Migration + backfill: existing documents/chunks land in the seeded default brain;
  single-brain behavior is unchanged after upgrade.
- Retrieval isolation: brain A never returns brain B's chunks.
- Brain resolution: explicit `?brain=` override vs default fallback.
- Model routing precedence: brain → global default → fallback, including the local
  provider and per-brain endpoint override.
- Ingestion happy paths: create brain + local-folder ingest; S3 ingest parameterized
  by brain.
- YAML export/import round-trip (config fidelity; secret handling per §14).

## 16. Build order (phases)

1. **Clone + verify** — §4; confirm build/boot/dashboard parity with msfg-rag.
2. **Data model** — `brains` table, `brain_id` columns, migration, `DefaultBrainSeeder`
   + backfill; existing behavior intact.
3. **Brain-scoped query + model routing** — per-request/default resolution; brain-aware
   `ModelRouterService`; local-LLM provider stub + per-brain endpoint override.
4. **Per-brain ingestion** — local folder + parameterized S3; "Sync now".
5. **Dashboard** — Brains screen, provider config (local), brain selector on existing
   screens, YAML export/import.
6. **Tests + docs** — §15; update README/RUNBOOK and `.env.example`.

## 17. Future extensions (out of scope)

Per-brain embedding spaces (multiple vector columns/tables), scheduled/auto-sync,
per-user authorization and brain ACLs, cross-brain federated queries, host
allowlisting for local/S3 endpoints.
