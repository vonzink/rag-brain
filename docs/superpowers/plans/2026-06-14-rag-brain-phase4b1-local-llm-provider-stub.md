# rag-brain Phase 4b-1 — Local-LLM Provider Stub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a selectable **`local`** AI provider for self-hosted / home-server LLMs (Ollama, LM Studio, vLLM, llama.cpp server — all OpenAI-compatible), configured by `LOCAL_LLM_BASE_URL` / `LOCAL_LLM_API_KEY` / `LOCAL_LLM_MODEL`. With per-brain model routing already live (P4a), any brain can then set `answer_provider=local` (or `utility_provider=local`) to run on the home server.

**Architecture:** Reuses the existing `OpenAiCompatibleProvider` adapter and the `ExtraProvidersConfig` key-conditional registration pattern (same as DeepSeek/Gemini/Grok). The local provider registers only when `LOCAL_LLM_BASE_URL` is set — no URL, no bean, not selectable. The global env URL is operator-trusted (like the other provider URLs), so **no SSRF surface is added here** — SSRF matters only for the per-brain `local_base_url` override, which is **P4b-2**. **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Tech Stack:** Java 21 · Spring Boot · Spring AI (OpenAI dialect) · JUnit 5.

---

## Context (verified)

- `ExtraProvidersConfig` (config/ExtraProvidersConfig.java) registers each optional provider as `new OpenAiCompatibleProvider(name, baseUrl, apiKey, model)` under `@Bean @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.X.api-key:}')")`. DeepSeek/Gemini/Grok condition on the **api-key**.
- `OpenAiCompatibleProvider(providerName, baseUrl, apiKey, defaultModel)` builds an `OpenAiChatModel` with a fixed `baseUrl`, `apiKey`, and `completionsPath("/chat/completions")`. `generate()` uses `request.model() != null ? request.model() : defaultModel`. So with a local base-url ending in `/v1` (e.g. `http://host:11434/v1`), the call resolves to `/v1/chat/completions` — correct for Ollama/LM Studio/vLLM.
- `application.yml` `brain.providers.*` block holds deepseek/gemini/grok with `base-url`/`api-key`/`model` each from env.
- `ModelRouterService.providerNames()` lists registered provider beans (so a registered `local` appears as available; per-brain routing from P4a will route to it when a brain selects `local`).
- **Difference for local:** home servers often ignore the API key, so condition the bean on **`base-url`** (not api-key), and tolerate a blank key/model.

---

### Task 0: Green baseline
- [ ] `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL`. Red → stop.

---

### Task 1: Register the `local` provider

**Files:**
- Modify: `src/main/resources/application.yml` (add `brain.providers.local.*`)
- Modify: `src/main/java/com/msfg/rag/config/ExtraProvidersConfig.java` (add the `localProvider` bean)
- Modify test: `src/test/java/com/msfg/rag/config/ExtraProvidersConfigTest.java`
- Modify: `.env.example` (document the `LOCAL_LLM_*` slots — the "API key location")

- [ ] **Step 1: Write the failing test**

In `ExtraProvidersConfigTest.java` (mirror the existing DeepSeek/Gemini/Grok cases — they use Spring's `ApplicationContextRunner` to assert a provider bean exists/absent by property), add two cases:
- with `brain.providers.local.base-url=http://localhost:11434/v1` set → a bean named/typed for provider `local` is present and `getProviderName()=="local"`.
- with no `brain.providers.local.base-url` → no local provider bean.
Follow the exact assertion style already in the file (match how it inspects the registered `OpenAiCompatibleProvider`s / context beans).

- [ ] **Step 2: Run → FAIL**: `./gradlew test --tests "com.msfg.rag.config.ExtraProvidersConfigTest"`.

- [ ] **Step 3: Add the config property**

In `application.yml` under `brain.providers:` (alongside deepseek/gemini/grok), add:
```yaml
    local:
      base-url: ${LOCAL_LLM_BASE_URL:}
      api-key: ${LOCAL_LLM_API_KEY:}
      model: ${LOCAL_LLM_MODEL:}
```

- [ ] **Step 4: Add the `localProvider` bean**

In `ExtraProvidersConfig.java` add (conditional on **base-url**, tolerant of a blank key — many local servers ignore it; pass a dummy so the client has a non-blank key):
```java
@Bean
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.local.base-url:}')")
public OpenAiCompatibleProvider localProvider(
        @Value("${brain.providers.local.base-url}") String baseUrl,
        @Value("${brain.providers.local.api-key:}") String apiKey,
        @Value("${brain.providers.local.model:}") String model) {
    // Local servers (Ollama/LM Studio/vLLM) usually ignore the key; OpenAiApi needs a non-blank one.
    String key = (apiKey == null || apiKey.isBlank()) ? "not-needed" : apiKey;
    return new OpenAiCompatibleProvider("local", baseUrl, key, model);
}
```

- [ ] **Step 5: Run → PASS**: `./gradlew test --tests "com.msfg.rag.config.ExtraProvidersConfigTest"`, then full `./gradlew test`.

- [ ] **Step 6: Document the slots in `.env.example`**

Add a block (near the other provider keys) to `.env.example`:
```bash
# --- Local / self-hosted LLM (Ollama, LM Studio, vLLM, llama.cpp server) ---
# OpenAI-compatible. Set the base URL to enable a selectable "local" provider; a
# brain can then use answer/utility_provider=local. Base URL should end in /v1
# (e.g. http://192.168.1.50:11434/v1 for Ollama). Many local servers ignore the
# API key — leave it blank or set any placeholder. Set the model to your served name.
LOCAL_LLM_BASE_URL=
LOCAL_LLM_API_KEY=
LOCAL_LLM_MODEL=
```

- [ ] **Step 7: Commit**
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 4b-1: local-LLM provider stub

Register a selectable OpenAI-compatible "local" provider (LOCAL_LLM_BASE_URL/
API_KEY/MODEL) for home-server LLMs (Ollama/LM Studio/vLLM), conditional on the
base URL. Document the env slots. A brain can now route to provider=local.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Verify

- [ ] **Step 1:** `./gradlew test` → green (incl. `ExtraProvidersConfigTest`; golden/compliance unchanged).
- [ ] **Step 2 (optional, no live server needed):** Boot on 8090 with `LOCAL_LLM_BASE_URL` unset → the `local` bean is absent, app starts clean, behavior identical to before. (A real home-server smoke is manual — out of scope.) Stop + `docker compose down`.
- [ ] **Step 3: Gate:** `local` provider registers iff `LOCAL_LLM_BASE_URL` set; `.env.example` documents the slots; full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/`.

---

## Self-Review

- **Spec coverage (§10 local-LLM stub):** env slots + key-conditional `local` bean (Task 1) + `.env.example` docs. Reuses `OpenAiCompatibleProvider`; conditions on base-url (local key optional). SSRF + per-brain endpoint correctly deferred to P4b-2.
- **Placeholder scan:** none — exact bean code, yaml, env block.
- **Consistency:** provider name `local`; `brain.providers.local.{base-url,api-key,model}` ← `LOCAL_LLM_{BASE_URL,API_KEY,MODEL}`; matches the deepseek/gemini/grok shape.

## Notes — P4b-2 (next)

Per-brain endpoint override + SSRF: when `ModelRouterService` resolves `provider=local` and the brain has `local_base_url` set, build/cache an `OpenAiCompatibleProvider` bound to the brain's endpoint (else the global one); a `LocalEndpointValidator` (SSRF) checks any per-brain base URL against `LOCAL_LLM_ALLOWED_HOSTS` and blocks loopback/link-local/private at write (P6) + call time. Then P5 source binding, P6 dashboard+security, P7 docs.
