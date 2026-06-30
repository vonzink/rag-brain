# RAG Brain — Runbook

How to start, use, and stop the brain and its dashboard on this machine.
Everything runs locally; nothing here touches production.

## Get your admin key

The dashboard lock screen wants the admin API key from `.env`:

```sh
cd ~/rag-brain
grep ADMIN_API_KEY .env          # prints the line; the key is after the "="
```

Copy it to the clipboard without printing it:

```sh
grep ADMIN_API_KEY .env | cut -d= -f2- | pbcopy
```

Open the whole `.env` in TextEdit (to read or change any setting):

```sh
open -e .env
```

After changing `.env`, restart the brain (Ctrl+C in its terminal, run the
start command again) — env values are read at startup.

## Start everything (two terminal tabs)

**Tab 1 — the brain (API on port 8091):**

```sh
cd ~/rag-brain && set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8091'
```

Ready when the log prints `Started MsfgRagApplication` (~10 s). Leave running.

**Tab 2 — the dashboard (port 5174):**

```sh
cd ~/rag-brain/dashboard && npm run dev -- --port 5174
```

Then open **http://localhost:5174** in a browser and unlock with the admin key.

## Stop everything

Press `Ctrl + C` in each terminal tab, or from anywhere:

```sh
lsof -ti:8091 -ti:5174 | xargs kill
```

## Dashboard screens

| Screen | What it does |
|---|---|
| Brains | Create/select brains and source bindings |
| Corpus | Documents in the brain; upload, sync, activate/deactivate, reindex |
| Personality | Public mode, disclaimer, allowed domains, token generation |
| Connect | Website widget installer and live verification |
| Connectors | Scoped server/agent/peer connectors and token rotation |
| Test Console | Ask the brain or run retrieval-only tests |
| Audit | Answers, confidence, retrieved sources, and traces |
| Rules/Vocabulary/Links/Page Guides | Control answer behavior and approved source/navigation links |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Brain won't start, database connection error | `docker compose up -d`, then start the brain again |
| "Port 8091 already in use" | An old copy is running: `lsof -ti:8091 \| xargs kill`, then start again |
| Dashboard says key rejected | Re-copy the key from `.env` (no spaces); the brain must be running first |
| Sync fails | AWS credentials: the same setup the `scripts/s3-ingest` tool uses must be available |
| Old process on port 8080 | A prior app may be running — check before killing: `lsof -nP -iTCP:8080 -sTCP:LISTEN` |

## Adding an AI provider

Three extra providers ship in the code — DeepSeek, Gemini, and Grok — but each
one only activates when its API key is present. Without a key the provider is
invisible to the dashboard.

**To activate a provider:**

1. Open `.env` in TextEdit:

   ```sh
   open -e ~/rag-brain/.env
   ```

2. Find the matching `..._API_KEY=` line and paste your key directly after the
   `=` (no spaces). For example:

   ```
   DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
   ```

3. Save the file and restart the brain (Ctrl+C in its terminal, then run the
   start command again).

4. The provider now appears in the **Settings** screen — pick it from the
   answer-lane or utility-lane dropdown and save.

**Before trusting a new provider with customer answers:** try it on the
**UTILITY** lane (reranker) first. Gemini and Grok are wired and ready but have
not been verified with a real key — routing borrower questions through them
before a quick sanity-check on the utility lane is a bigger risk than it looks.

**Data-handling reminder:** routing borrower questions to any new vendor means
that data leaves Anthropic/OpenAI — confirm the vendor's data-handling terms
are acceptable before enabling a new provider on the answer lane in production.

## Corpus updates

Drop files into `s3://msfg.us/rag-brain/` (and list them in `_manifest.json`
if they need a title/source type or should be excluded with `ingest: false`),
then press **Sync now** on the Corpus screen. **Dry run** first shows what
would change without changing anything.
