# rag-brain Phase 4b-2 — Per-Brain Local-LLM Endpoint + SSRF Allowlist Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let a brain target its **own** home-server LLM via a per-brain `local_base_url`, validated by an SSRF allowlist, and settable from the dashboard. When `ModelRouterService` resolves `provider=local` for a brain with `local_base_url` set, it routes to that endpoint; otherwise the global `local` provider (P4b-1) handles it.

**Architecture:** A `LocalEndpointValidator` (SSRF: http/https only, **always block link-local 169.254/16 — the cloud-metadata target**; optional strict `LOCAL_LLM_ALLOWED_HOSTS` allowlist; localhost + LAN allowed by default so home servers work). `ModelRouterService` gains a per-base-URL cache of `OpenAiCompatibleProvider`s built (and validated) on first use, with the global `LOCAL_LLM_API_KEY` (local servers usually ignore the key). `local_base_url` is a **URL, not a secret**, so it is exposed in the admin DTO + create/update (SSRF-validated at write). The per-brain **api key stays out of the dashboard** (global key used; per-brain key needs encryption — deferred). **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Tech Stack:** Java 21 · Spring Boot · Spring AI (OpenAI dialect) · `java.net.InetAddress` · React/TS · JUnit 5 + Mockito.

---

## Context (verified, current code)

- `ModelRouterService.generate(AiRequest, UUID brainId)` (post-P4a): loads the `Brain`, `resolve(brain, purpose)` → `ResolvedModel(provider, model)`, then `providers.get(resolved.provider())` (falls to default if unregistered), `primary.generate(request.withModel(resolved.model()))`, fallback on error with `withModel(null)`. Constructor injects `List<AiModelProvider>`, `RagProperties`, `RuntimeSettings`, `BrainRepository`.
- `OpenAiCompatibleProvider(String providerName, String baseUrl, String apiKey, String defaultModel)` builds a Spring AI client bound to `baseUrl` (fixed) with `completionsPath("/chat/completions")`; `generate()` uses `request.model() != null ? request.model() : defaultModel`. So a per-brain client is keyed by `baseUrl`; the model is passed per-request via `withModel`.
- P4b-1 added `brain.providers.local.{base-url,api-key,model}` (`LOCAL_LLM_*`) + a global `local` provider bean conditional on `base-url`.
- `Brain` has `getLocalBaseUrl()` / `setLocalBaseUrl(...)` and `getLocalApiKeyRef()` (Phase 2 columns).
- `BrainAdminController`: `BrainDto` (15 fields, NO `localBaseUrl`/`localApiKeyRef`), `CreateBrainRequest` (13 fields incl. `disclaimer`), `UpdateBrainRequest` (12 fields), `apply(...)` sets source+model fields; create/update validate + call `apply`. `requireSourceBinding` enforces local/s3. `GlobalExceptionHandler` maps `IllegalArgumentException`→400.
- `RagProperties` is `msfg.rag.*`; the local config is under `brain.*` (read via `@Value`).

---

### Task 0: Green baseline
- [ ] `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL`. Red → stop.

---

### Task 1: `LocalEndpointValidator` (SSRF)

**Files:**
- Create: `src/main/java/com/msfg/rag/service/ai/LocalEndpointValidator.java`
- Test: `src/test/java/com/msfg/rag/service/ai/LocalEndpointValidatorTest.java`
- Modify: `src/main/resources/application.yml` (+`brain.local-llm.allowed-hosts`), `.env.example` (+`LOCAL_LLM_ALLOWED_HOSTS`)

- [ ] **Step 1: Write the failing test**
```java
package com.msfg.rag.service.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalEndpointValidatorTest {

    private final LocalEndpointValidator permissive = new LocalEndpointValidator("");
    private final LocalEndpointValidator allowlisted = new LocalEndpointValidator("192.168.1.50, ollama.lan");

    @Test
    void allowsLocalhostAndLanWhenPermissive() {
        permissive.validate("http://localhost:11434/v1");
        permissive.validate("http://127.0.0.1:1234/v1");
        permissive.validate("http://192.168.1.50:11434/v1");
        permissive.validate("https://lm.example.com/v1");
    }

    @Test
    void blocksLinkLocalMetadataAlways() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("http://169.254.169.254/latest/meta-data"));
        assertThrows(IllegalArgumentException.class, () -> allowlisted.validate("http://169.254.169.254/"));
    }

    @Test
    void rejectsNonHttpSchemeAndMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("ftp://192.168.1.50/v1"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("not a url"));
    }

    @Test
    void allowlistRestrictsToListedHosts() {
        allowlisted.validate("http://192.168.1.50:11434/v1");   // in list
        assertThrows(IllegalArgumentException.class, () -> allowlisted.validate("http://192.168.1.99:11434/v1")); // not in list
    }
}
```

- [ ] **Step 2: Run → FAIL** (class missing).

- [ ] **Step 3: Implement the validator**
```java
package com.msfg.rag.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSRF guard for a brain's local-LLM base URL. http/https only; the cloud-metadata
 * link-local range (169.254/16, fe80::/10) is ALWAYS blocked; localhost + LAN are
 * allowed by default (home servers). An optional LOCAL_LLM_ALLOWED_HOSTS allowlist
 * restricts to exact hosts when set. Throws IllegalArgumentException (-> 400).
 */
@Component
public class LocalEndpointValidator {

    private final Set<String> allowedHosts;

    public LocalEndpointValidator(@Value("${brain.local-llm.allowed-hosts:}") String allowed) {
        this.allowedHosts = Arrays.stream(allowed.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.US))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid local LLM base URL: " + baseUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("Local LLM base URL must be http or https: " + baseUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Local LLM base URL has no host: " + baseUrl);
        }
        // Always block link-local (cloud-metadata 169.254.169.254 etc.) — resolve so a
        // hostname pointing at link-local is caught too.
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLinkLocalAddress()) {
                    throw new IllegalArgumentException(
                            "Local LLM host resolves to a blocked link-local address: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Local LLM host cannot be resolved: " + host);
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.US))) {
            throw new IllegalArgumentException(
                    "Local LLM host '" + host + "' is not in LOCAL_LLM_ALLOWED_HOSTS");
        }
    }
}
```

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Config** — `application.yml` under `brain:` add `local-llm:\n  allowed-hosts: ${LOCAL_LLM_ALLOWED_HOSTS:}`; `.env.example` add (near `LOCAL_LLM_*`):
```bash
# Optional SSRF allowlist for per-brain local LLM endpoints (comma-separated hosts).
# Empty = allow localhost + any LAN/public host EXCEPT the link-local/metadata range.
# Set to lock per-brain endpoints to specific hosts, e.g. 192.168.1.50,ollama.lan
LOCAL_LLM_ALLOWED_HOSTS=
```

- [ ] **Step 6: Commit** (`Phase 4b-2: LocalEndpointValidator (SSRF for per-brain local endpoints)`).

---

### Task 2: Per-brain local provider in `ModelRouterService`

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ai/ModelRouterService.java`
- Modify test: `src/test/java/com/msfg/rag/service/ai/ModelRouterServiceTest.java`

- [ ] **Step 1: Write the failing test** — a brain with `localBaseUrl="http://192.168.1.50:11434/v1"` and `answerProvider="local"`, `answerModel="llama3"` → an ANSWER request routes to a provider whose `generate` is called with the brain's model `llama3` and (verified) NOT the global `local` bean. A brain with `provider=local` and NO `localBaseUrl` → uses the registered global `local` bean (or default if absent). Use Mockito; you'll need to verify the per-brain `OpenAiCompatibleProvider` was used — simplest: assert the response routed and that the cache builds one (you can spy/assert via the validator being called). Mirror the existing `ModelRouterServiceTest` setup (mocked `BrainRepository`, providers, `RuntimeSettings`); add a mocked/real `LocalEndpointValidator` and the global-key `@Value` (pass via constructor).

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** — add to `ModelRouterService`:
  - Constructor params: `LocalEndpointValidator localEndpointValidator`, `@Value("${brain.providers.local.api-key:}") String localApiKey`. Store them; store `this.localApiKey = (localApiKey == null || localApiKey.isBlank()) ? "not-needed" : localApiKey;`.
  - A cache: `private final Map<String, AiModelProvider> localProviderCache = new java.util.concurrent.ConcurrentHashMap<>();`
  - In `generate(...)`, replace the `providers.get(resolved.provider())` primary selection with:
```java
AiModelProvider primary;
if ("local".equals(resolved.provider())
        && brain.getLocalBaseUrl() != null && !brain.getLocalBaseUrl().isBlank()) {
    primary = localProviderFor(brain.getLocalBaseUrl());   // per-brain endpoint
} else {
    primary = providers.get(resolved.provider());
    if (primary == null) {
        log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                request.purpose() == AiRequest.Purpose.UTILITY ? "utility" : "answer",
                resolved.provider(), routing.defaultProvider());
        primary = providers.get(routing.defaultProvider());
    }
}
```
  - Add:
```java
private AiModelProvider localProviderFor(String baseUrl) {
    return localProviderCache.computeIfAbsent(baseUrl, url -> {
        localEndpointValidator.validate(url);   // SSRF check at first use (also enforced at write time)
        return new com.msfg.rag.provider.OpenAiCompatibleProvider("local", url, localApiKey, "");
    });
}
```
  The fallback-on-error block is unchanged (still `withModel(null)` to the configured fallback provider).

- [ ] **Step 4: Fix `ModelRouterServiceTest`** — its `new ModelRouterService(...)` calls now need the two extra constructor args (`LocalEndpointValidator` — a real `new LocalEndpointValidator("")` is fine — and a local api key string). Keep all existing assertions.

- [ ] **Step 5: Run → PASS** (`ModelRouterServiceTest`), then full `./gradlew test`.

- [ ] **Step 6: Commit** (`Phase 4b-2: route provider=local to a brain's own endpoint`).

---

### Task 3: Expose + SSRF-validate `local_base_url` in the admin API

**Files:**
- Modify: `src/main/java/com/msfg/rag/controller/BrainAdminController.java`
- Modify test: `src/test/java/com/msfg/rag/controller/BrainAdminControllerTest.java`

- [ ] **Step 1: Write failing tests** — create/update with a valid `localBaseUrl` persists it and it appears in the `BrainDto`; create/update with `http://169.254.169.254/` → `IllegalArgumentException` (400); `localApiKeyRef`/api key never appears in any DTO (keep the existing secret-leak assertion; `localBaseUrl` IS allowed in the DTO since it is not a secret).

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement**
  - Inject `LocalEndpointValidator localEndpointValidator` (constructor + field).
  - `BrainDto`: add `String localBaseUrl` (after `localPath`); `from(...)` adds `b.getLocalBaseUrl()`. (Do NOT add `localApiKeyRef`.)
  - `CreateBrainRequest` + `UpdateBrainRequest`: add a trailing `String localBaseUrl` field.
  - `apply(...)`: add a `String localBaseUrl` parameter; `brain.setLocalBaseUrl(trimToNull(localBaseUrl));`. Update both `apply(...)` call sites (create + update) to pass `req.localBaseUrl()`.
  - In `create` and `update`, after `requireSourceBinding(...)`: `if (!isBlank(req.localBaseUrl())) localEndpointValidator.validate(req.localBaseUrl().trim());` (SSRF at write → 400).
  - Update every `new CreateBrainRequest(...)` / `new UpdateBrainRequest(...)` / `BrainDto` construction site the compiler flags (tests included) for the new trailing field.

- [ ] **Step 4: Run → PASS**, then full `./gradlew test`.

- [ ] **Step 5: Commit** (`Phase 4b-2: set + SSRF-validate per-brain local_base_url via admin API`).

---

### Task 4: Frontend — per-brain endpoint field

**Files:**
- Modify: `dashboard/src/types.ts`, `dashboard/src/api.ts` (if the request type lives there), `dashboard/src/screens/Brains.tsx`

- [ ] **Step 1:** Add `localBaseUrl?: string` to `BrainAdminDto` + the create/update request types.
- [ ] **Step 2:** In `Brains.tsx`, add an optional **"Local LLM base URL"** text input to the create/update form (mirror the existing field pattern; a `muted` hint: "Only for a brain using a `local` provider — e.g. `http://192.168.1.50:11434/v1`"). Send `localBaseUrl` (trimmed; omit when empty). Show the brain's `localBaseUrl` in the list's source/model summary if set.
- [ ] **Step 3:** Build green: `cd dashboard && npm run check && npm run build` (and `npm test`). Existing screens still compile.
- [ ] **Step 4: Commit** (`Phase 4b-2: dashboard per-brain local LLM endpoint field`).

---

### Task 5: Regression + verify

- [ ] **Step 1:** Full `./gradlew test` → green (golden/compliance/router/brain-admin all pass).
- [ ] **Step 2 (smoke, optional):** boot on a free port (8091; user runs msfg-rag on 8090): create a brain with `answerProvider=local`, `localBaseUrl=http://127.0.0.1:9/v1` (an unreachable but VALID local URL) → 200 (SSRF passes); an ask would attempt that endpoint and fail-over (fallback provider) — fine, proves routing reached the per-brain endpoint. Create with `localBaseUrl=http://169.254.169.254/` → 400. Stop + `docker compose down`.
- [ ] **Step 3: Gate:** SSRF blocks link-local everywhere (validator + write); `provider=local` + `localBaseUrl` routes to the brain's endpoint; no secret (`local_api_key`) exposed; full suite green; dashboard builds; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only its pre-existing untracked file(s).

---

## Self-Review

- **Spec coverage (§10/§14):** per-brain endpoint override (Task 2), SSRF allowlist + always-block-metadata (Task 1), settable from dashboard with write-time SSRF (Tasks 3–4). Per-brain api-key intentionally deferred (uses global key; note in Deferred).
- **Placeholder scan:** none — full validator, router change, DTO edits, test shapes.
- **Security:** link-local (metadata) blocked at validator construction-time use AND at admin write-time; `local_base_url` is a URL (not a secret) so exposing it is fine; `local_api_key` stays out of DTOs/forms.
- **Consistency:** provider name `local`; cache keyed by base URL; model passed per-request via `withModel`; `IllegalArgumentException`→400.

## Deferred
Per-brain local **api key** (needs encryption/Secrets-Manager + redaction — out of scope; global `LOCAL_LLM_API_KEY` used, fine for key-less local servers); richer endpoint health-check in the UI; per-brain embedding endpoint.
