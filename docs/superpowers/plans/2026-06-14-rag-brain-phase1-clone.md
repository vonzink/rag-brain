# rag-brain Phase 1 — Clone & Verify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a clean-template clone of `msfg-rag` at `/Users/zacharyzink/rag-brain` that builds, tests, and boots with behavior identical to the source — the verified foundation for the multi-brain feature work.

**Architecture:** Selective file copy (source minus regenerable artifacts, git history, secrets-in-vcs, and the Docker data volume) into the already-initialized `rag-brain` git repo, followed by a build → test → boot → dashboard verification gate. No source code is modified in this phase; it is verification-driven, not TDD. The TDD discipline begins in Phase 2 where real code is written.

**Tech Stack:** Java 21 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL 16 + pgvector · Flyway · Gradle · Vite + TypeScript/React · Docker · rsync · git

---

## Context the engineer needs

- **Source:** `/Users/zacharyzink/MSFG/msfg-rag` (a working RAG backend; see its `README.md`).
- **Target:** `/Users/zacharyzink/rag-brain` — already a git repo on branch `master`, already containing this plan and the design spec under `docs/superpowers/`. **Do not delete or overwrite those.**
- **Design spec:** `/Users/zacharyzink/rag-brain/docs/superpowers/specs/2026-06-14-rag-brain-multi-brain-design.md` (§4 defines clone scope).
- **`.gitignore` already excludes** `.env` and `data/`, so those are carried over **on disk** but intentionally **not committed**. That is correct — secrets and corpus stay local.
- **The vector data is NOT in the repo.** Document chunks/embeddings live in the Docker volume `msfg_rag_pgdata`, which a clean clone does not copy. rag-brain therefore boots with an **empty** database (Flyway creates the schema; there are zero document chunks until you re-ingest `data/documents`). An empty msfg-rag behaves the same way, so "boots + schema + pack loads + ask endpoint returns a low-confidence refusal" IS valid parity for this phase.
- **Port/container collision:** `docker-compose.yml` hardcodes `container_name: msfg-rag-postgres` and host port `5433`. If msfg-rag's Postgres is running, rag-brain's `docker compose up` fails. Verification below stops the source stack first. Task 9 (optional) makes rag-brain independently runnable.
- **Known issue (from project memory):** host port `8080` is sometimes contended. The boot smoke test below uses `--server.port=8090` to sidestep it.

---

### Task 1: Pin the source revision and run pre-flight checks

**Files:**
- None created/modified (inspection only).

- [ ] **Step 1: Record the exact source commit** (the user edits msfg-rag live; pin it for a reproducible clone)

Run:
```bash
git -C /Users/zacharyzink/MSFG/msfg-rag rev-parse HEAD
git -C /Users/zacharyzink/MSFG/msfg-rag status --short
```
Expected: a 40-char SHA (save it — it goes in the Task 8 commit message). A clean (empty) status is ideal; if there are uncommitted changes, note that the clone captures the working tree, not just the commit.

- [ ] **Step 2: Confirm the target repo is the intended empty-but-initialized state**

Run:
```bash
git -C /Users/zacharyzink/rag-brain status --short
git -C /Users/zacharyzink/rag-brain log --oneline
ls -A /Users/zacharyzink/rag-brain
```
Expected: branch `master`; one commit ("Add multi-brain template design spec"); contents are only `.git` and `docs/`.

- [ ] **Step 3: Confirm tooling is present**

Run:
```bash
java -version 2>&1 | head -1
docker version --format '{{.Server.Version}}' 2>/dev/null || echo "DOCKER NOT RUNNING"
rsync --version | head -1
```
Expected: JDK 21.x; a Docker server version (Docker Desktop running); rsync present. If Docker is not running, start Docker Desktop before Task 5.

---

### Task 2: Selective copy of the source tree

**Files:**
- Create (many): all source/config/dashboard/packs/docs/scripts/data files under `/Users/zacharyzink/rag-brain/`.

- [ ] **Step 1: Stop the source Postgres stack to free the container name + port for later verification**

Run:
```bash
( cd /Users/zacharyzink/MSFG/msfg-rag && docker compose down )
```
Expected: `msfg-rag-postgres` removed (or "no containers" if it was not running). The named volume `msfg_rag_pgdata` is preserved — msfg-rag's data is untouched.

- [ ] **Step 2: rsync the tree, excluding artifacts / history / data-volume-only items**

Run:
```bash
rsync -a \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='build/' \
  --exclude='**/node_modules/' \
  --exclude='dashboard/dist/' \
  --exclude='.DS_Store' \
  --exclude='data/test-extract/' \
  /Users/zacharyzink/MSFG/msfg-rag/ /Users/zacharyzink/rag-brain/
```
Expected: completes with no errors. The trailing slash on the source copies its *contents* into rag-brain; without `--delete`, rag-brain's existing `.git/` and `docs/superpowers/` are preserved (the source's own `docs/` files are merged alongside).

---

### Task 3: Verify what landed (and what didn't)

**Files:**
- None created/modified (inspection only).

- [ ] **Step 1: Confirm expected top-level entries are present**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ls -A
```
Expected to see: `.claude .env .env.example .gitignore README.md build.gradle.kts dashboard data docker-compose.yml docs gradle gradlew gradlew.bat packs scripts settings.gradle.kts src` (plus `.git`).

- [ ] **Step 2: Confirm excluded artifacts are absent**

Run:
```bash
cd /Users/zacharyzink/rag-brain && \
for p in build .gradle dashboard/node_modules dashboard/dist data/test-extract; do
  [ -e "$p" ] && echo "PRESENT (unexpected): $p" || echo "absent (good): $p"
done
```
Expected: all five reported `absent (good)`.

- [ ] **Step 3: Confirm the carried-over secrets/data and preserved planning docs**

Run:
```bash
cd /Users/zacharyzink/rag-brain && \
ls -l .env && ls data/documents | head && \
ls docs/superpowers/specs docs/superpowers/plans
```
Expected: `.env` present (mode `-rw-------`); `data/documents` lists the source documents; both the spec and this plan are still under `docs/superpowers/`.

- [ ] **Step 4: Confirm the source pack came across**

Run:
```bash
ls /Users/zacharyzink/rag-brain/packs/msfg-mortgage
```
Expected: `pack.yaml classifier.yaml guardrails.yaml prompt.yaml retrieval.yaml`.

---

### Task 4: Compile the backend

**Files:**
- Create: `/Users/zacharyzink/rag-brain/build/` (generated by Gradle).

- [ ] **Step 1: Compile without running tests (fast feedback that the copy is intact)**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew clean compileJava compileTestJava
```
Expected: `BUILD SUCCESSFUL`. If `gradlew` is not executable, run `chmod +x gradlew` and retry (rsync `-a` should have preserved the bit).

---

### Task 5: Run the full test suite

**Files:**
- None modified (verification). Tests use Testcontainers, which start their own ephemeral Postgres via Docker — the docker-compose stack does **not** need to be up for this.

- [ ] **Step 1: Ensure Docker is running, then run tests**

Run:
```bash
cd /Users/zacharyzink/rag-brain && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests passing — including `AnswerValidationServiceTest` (the compliance gate). A Testcontainers/Docker error here means Docker isn't running, not a clone defect.

- [ ] **Step 2: If any test fails, triage before proceeding**

Open the report at `build/reports/tests/test/index.html`. A clean clone should match the source exactly; a failure indicates a missing copied file or an environment gap (Docker, JDK), not a code change. Resolve before continuing — do not edit tests to make them pass.

---

### Task 6: Boot the backend and smoke-test the pipeline

**Files:**
- None modified (verification).

- [ ] **Step 1: Start rag-brain's Postgres (source stack was stopped in Task 2)**

Run:
```bash
cd /Users/zacharyzink/rag-brain && docker compose up -d && \
docker compose ps
```
Expected: `msfg-rag-postgres` healthy on host port `5433`. (Container name is still the source's — Task 9 renames it.)

- [ ] **Step 2: Boot the app on port 8090 (avoids the known 8080 contention)**

Run:
```bash
cd /Users/zacharyzink/rag-brain && set -a && source .env && set +a && \
./gradlew bootRun --args='--server.port=8090'
```
Expected (in logs): Flyway applies `V1__init_schema` to the empty DB; the domain pack loads (slug `mortgage`); `Started MsfgRagApplication in N seconds`; no stack traces. Leave it running for Step 3 (use a second terminal).

- [ ] **Step 3: Smoke-test the ask endpoint (proves the full pipeline runs end-to-end)**

Run (second terminal):
```bash
curl -s -X POST http://localhost:8090/api/ai/mortgage/ask \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"clone-smoke-1","question":"Can I use gift funds for my down payment?","loanType":"conventional","state":"CO"}' | head -c 1200; echo
```
Expected: HTTP 200 with a JSON body containing `answer`, `citations`, `confidence`, `humanEscalationRequired`, `disclaimer`. Because the DB has no chunks yet, this should be a **low-confidence refusal / loan-officer referral** — that is the correct, parity-preserving result for an empty corpus and confirms classifier → retrieval → prompt → model → validation all execute.

- [ ] **Step 4: Stop the app**

Press `Ctrl+C` in the bootRun terminal. Optionally `docker compose down` (or leave Postgres up for Task 9).

---

### Task 7: Install and run the dashboard

**Files:**
- Create: `/Users/zacharyzink/rag-brain/dashboard/node_modules/` (generated by npm).

- [ ] **Step 1: Install dashboard dependencies (node_modules was intentionally not copied)**

Run:
```bash
cd /Users/zacharyzink/rag-brain/dashboard && npm install
```
Expected: dependencies install with no errors; `node_modules/` appears.

- [ ] **Step 2: Build the dashboard to confirm the TypeScript project is intact**

Run:
```bash
cd /Users/zacharyzink/rag-brain/dashboard && npm run build
```
Expected: a successful Vite build producing `dist/` (TypeScript compiles cleanly). `npm run dev` is also available for an interactive check on the Vite dev port.

---

### Task 8: Commit the clone

**Files:**
- Modify: git index of `/Users/zacharyzink/rag-brain`.

- [ ] **Step 1: Stage everything (gitignore auto-excludes `.env`, `data/`, `build/`, `.gradle/`, `node_modules/`, `dist/`)**

Run:
```bash
cd /Users/zacharyzink/rag-brain && git add -A && git status --short | head -40
```
Expected: source/config/dashboard-source/packs/docs/scripts files staged. Confirm **`.env`, `data/`, `build/`, `.gradle/`, `dashboard/node_modules`, and `dashboard/dist` are NOT staged**. If any appear, stop and fix the ignore rules before committing (the `.env`/`data` exclusion is a hard requirement).

- [ ] **Step 2: Commit, referencing the pinned source SHA from Task 1**

Run (replace `<SOURCE_SHA>` with the SHA recorded in Task 1, Step 1):
```bash
cd /Users/zacharyzink/rag-brain && git commit -q -m "$(cat <<'EOF'
Clone source from msfg-rag

Clean-template clone of msfg-rag (source commit <SOURCE_SHA>): all source,
packs, config, dashboard source, docs, scripts, and data/documents. Excludes
git history, build artifacts, node_modules, dist, and the Postgres data volume.
Verified: gradle build + tests pass, app boots, ask endpoint responds, dashboard builds.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)" && git log --oneline
```
Expected: a new commit "Clone source from msfg-rag" on top of the spec commit.

---

### Task 9 (OPTIONAL, recommended): Make rag-brain independently runnable alongside msfg-rag

Pure-clone parity is verified by Task 6. But rag-brain and msfg-rag share a container name, host port (`5433`), DB name (`msfg_rag`), and Docker volume — so they cannot run at the same time. Because rag-brain is meant to be a reusable, independent template, rename these. Skip this task if you only ever run one at a time. This is the first *intentional modification* beyond the exact copy.

**Files:**
- Modify: `/Users/zacharyzink/rag-brain/docker-compose.yml`
- Modify: `/Users/zacharyzink/rag-brain/.env` (DB connection) and `/Users/zacharyzink/rag-brain/.env.example`

- [ ] **Step 1: Rename the compose identifiers**

In `docker-compose.yml`, change: `container_name: msfg-rag-postgres` → `rag-brain-postgres`; `POSTGRES_DB: msfg_rag` → `rag_brain`; `POSTGRES_USER: msfg_rag` → `rag_brain`; host port `"5433:5432"` → `"5434:5432"`; volume `msfg_rag_pgdata` → `rag_brain_pgdata` (in both the service `volumes:` and the top-level `volumes:` block).

- [ ] **Step 2: Update the DB connection to match**

In `.env` (and mirror the new values in `.env.example`), update the datasource to the renamed DB and port, e.g. `DB_URL=jdbc:postgresql://localhost:5434/rag_brain`, `DB_USERNAME=rag_brain`, `DB_PASSWORD=local_dev_only`. Match whatever key names `.env` actually uses (confirm by reading `.env` / `application.yml`).

- [ ] **Step 3: Re-verify boot on the renamed stack**

Run:
```bash
cd /Users/zacharyzink/rag-brain && docker compose up -d && docker compose ps && \
set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090'
```
Expected: `rag-brain-postgres` healthy on host port `5434`; Flyway initializes the fresh `rag_brain` DB; app starts cleanly. Confirm both stacks can be up together: `docker -C / ps` shows `msfg-rag-postgres` (if started) and `rag-brain-postgres` with no conflict. Stop with `Ctrl+C` + `docker compose down`.

- [ ] **Step 4: Commit the independence change**

Run:
```bash
cd /Users/zacharyzink/rag-brain && git add docker-compose.yml .env.example && git commit -q -m "$(cat <<'EOF'
Make rag-brain runnable alongside msfg-rag

Rename Postgres container, db/user, host port (5434), and data volume so
rag-brain no longer collides with the source stack. (.env is gitignored;
update it locally to the renamed DB.)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
Expected: a commit updating `docker-compose.yml` and `.env.example` (note `.env` itself is gitignored and changed only on disk).

---

### Task 10: Phase 1 completion gate

**Files:**
- None (checklist).

- [ ] **Step 1: Confirm every gate is green before declaring Phase 1 done**

  - [ ] Source SHA recorded and referenced in the clone commit.
  - [ ] Expected files present; artifacts/history/`test-extract` absent (Task 3).
  - [ ] `./gradlew build`/`test` pass, including `AnswerValidationServiceTest` (Tasks 4–5).
  - [ ] App boots; ask endpoint returns a structured low-confidence refusal on the empty corpus (Task 6).
  - [ ] Dashboard installs and builds (Task 7).
  - [ ] `.env` and `data/` are on disk but NOT committed (Task 8).
  - [ ] (If Task 9 done) both stacks run side by side.

- [ ] **Step 2: Optional — re-ingest the corpus to verify answer parity**

To confirm rag-brain answers from the corpus (not just refuses), re-ingest `data/documents` via the admin upload API (see `README.md` "Loading guideline documents") or the S3/local ingest script, then re-run the Task 6 ask — it should now answer with citations. This rebuilds the vector data that the Docker volume held in the source. Optional for Phase 1; required before judging retrieval quality.

---

## Self-Review

- **Spec coverage (§4 clone scope):** copy set (Task 2) and exclude set match spec §4; fresh git history preserved (rag-brain has its own repo); `.env`/`data` carried on disk but uncommitted (Tasks 2, 8); verification gate (Tasks 4–7, 10). The DB-volume nuance and corpus re-ingest (Task 10 Step 2) are documented, closing the "works out of the box" gap. Phases 2–6 are intentionally out of scope for this plan and will be planned against the real cloned source.
- **Placeholder scan:** no TBD/TODO; the only intentional fill-in is `<SOURCE_SHA>` (Task 8) and the `.env` key names in Task 9 (to be confirmed against the actual file), both explicitly flagged.
- **Consistency:** ports (8090 for app, 5433/5434 for DB), container/volume names, and endpoint paths are used consistently across tasks.

## Notes for Phases 2–6

After this plan is executed and verified, write per-phase plans (data model + migration; brain-scoped query + model routing + local-LLM stub; per-brain ingestion; dashboard + YAML; tests + docs) by **reading the actual cloned Java/TS source** so every task carries real signatures and code, not guesses.