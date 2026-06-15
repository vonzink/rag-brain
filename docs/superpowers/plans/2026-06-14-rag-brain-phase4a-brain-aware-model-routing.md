# rag-brain Phase 4a — Brain-Aware Model Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make `ModelRouterService` resolve the answer/utility model **per brain** — reading each brain's `answer_/utility_provider`+`model` columns (already on `brains` from Phase 2), threading the `brainId` from the request, and falling back to the global `RuntimeSettings` defaults.

**Architecture:** Phase 4a of the co-resident multi-brain design (spec §8). `generate(AiRequest)` becomes `generate(AiRequest, UUID brainId)`; it loads the `Brain` and resolves a **paired** `(provider, model)` from the brain's column for the request's `Purpose` (ANSWER vs UTILITY) when set, else from global settings — preserving the existing invariant that a model name never crosses providers. The fallback-provider-on-error path is unchanged. `brainId` is threaded from the two `generate` call sites (`AskService` answer lane; `RerankerService` utility lane via `RetrievalService.retrieve`). No new providers here — P4b adds the local-LLM stub. **All work in `/Users/zacharyzink/rag-brain`; never touch `/Users/zacharyzink/MSFG/msfg-rag`.**

**Tech Stack:** Java 21 · Spring Boot · Spring Data JPA · JUnit 5 + Mockito + Testcontainers.

---

## Context (verified, current code)

- `ModelRouterService.generate(AiRequest request)` (ModelRouterService.java:58-85): `boolean utility = request.purpose()==UTILITY;` resolves `configuredProvider`/`model` from `RuntimeSettings` (`utilityProvider()/utilityModel()` or `answerProvider()/answerModel()`), looks up the provider bean (falls to `routing.defaultProvider()` if unregistered), calls `primary.generate(request.withModel(model))`, and on exception falls back to `routing.fallbackProvider()` with `withModel(null)`. Providers are a `Map<String,AiModelProvider>` keyed by `getProviderName()`.
- `RuntimeSettings` (global, backed by `brain_settings`): `answerProvider()` defaults to env `routing.defaultProvider` (anthropic); `answerModel()` defaults to `null` (provider default); `utilityProvider()` defaults to `answerProvider()`; `utilityModel()` returns the answer model only when utility & answer providers match, else `null`.
- `AiRequest` (record): `purpose()` ∈ {ANSWER, UTILITY}; factories `forGuidelineAnswer(prompt)` (ANSWER), `forUtility(prompt,temp,maxTokens)` (UTILITY); `withModel(model)`.
- **`generate` call sites (only two):**
  - `AskService.java:107-108`: `modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt))` — `AskService.ask(request, brainId)` already has `brainId` (Phase 3a).
  - `RerankerService.java:69-70`: `modelRouterService.generate(AiRequest.forUtility(prompt, 0.0, 800))` inside `rerank(question, candidates, topK)`. `rerank` is called from `RetrievalService.java:131` (`retrieve(question, brainId)` has `brainId`).
- `Brain` entity getters exist (Phase 2): `getAnswerProvider()`, `getAnswerModel()`, `getUtilityProvider()`, `getUtilityModel()`. `BrainRepository.findById(UUID)`.
- **Behavior note (intentional, document it):** the Phase-2 `DefaultBrainSeeder` set the default brain's `utility_provider=openai`, `utility_model=gpt-4.1-nano` (the configured cheap-utility lane). The pre-routing system left utility unconfigured, so it defaulted to the answer provider (anthropic). Activating per-brain routing therefore makes the platform's intended ANSWER(anthropic)/UTILITY(openai cheap) split take effect — the reranker now runs on `gpt-4.1-nano`. `OPENAI_API_KEY` is already configured (embeddings use it). This is the designed behavior, NOT a regression; do not "fix" the seeder.

---

### Task 0: Green baseline
- [ ] `cd /Users/zacharyzink/rag-brain && ./gradlew test` → `BUILD SUCCESSFUL` (Docker required). Red → stop.

---

### Task 1: `ModelRouterService` brain-aware + thread `brainId`

**Files:**
- Modify: `service/ai/ModelRouterService.java` (constructor + `generate` + a resolver)
- Modify: `service/AskService.java` (the `generate` call at :107-108)
- Modify: `service/retrieval/RerankerService.java` (`rerank` signature + the `generate` call)
- Modify: `service/retrieval/RetrievalService.java` (the `rerank` call at :131)
- Modify tests: `service/ai/ModelRouterServiceTest.java`, and any test that mocks `modelRouterService.generate` or calls `rerank` (`AskServiceTest`, `AskServiceBrainTest`, `RerankerServiceTest`, `RetrievalServiceTest` — compiler/Mockito will surface them)

- [ ] **Step 1: Write the failing test (brain-aware routing)**

Rewrite/extend `ModelRouterServiceTest` so the router takes a `brainId` and a mocked `BrainRepository`. Assert: (a) when the brain's `answerProvider/answerModel` are set, an ANSWER request routes to THAT provider with THAT model; (b) when the brain's `utilityProvider/utilityModel` are set, a UTILITY request routes to them; (c) when a brain column is null/blank, it falls back to the global `RuntimeSettings` value; (d) the existing fallback-on-error path still works. Example shape (adapt to the existing test's provider mocks/helpers):
```java
// given a brain with answerProvider="anthropic", answerModel="claude-x", utilityProvider="openai", utilityModel="gpt-x"
when(brainRepository.findById(BRAIN)).thenReturn(Optional.of(brain));
when(anthropic.generate(any())).thenReturn(answerResp);
var routed = router.generate(AiRequest.forGuidelineAnswer("p"), BRAIN);
verify(anthropic).generate(argThat(r -> "claude-x".equals(r.model())));  // brain's model, paired with brain's provider
assertFalse(routed.fallbackUsed());
// UTILITY routes to openai/gpt-x; a brain with null answerProvider falls back to settings.answerProvider()
```

- [ ] **Step 2: Run → FAIL** (`generate(AiRequest, UUID)` / `BrainRepository` not wired): `./gradlew test --tests "com.msfg.rag.service.ai.ModelRouterServiceTest"`.

- [ ] **Step 3: Make `ModelRouterService` brain-aware**

Inject `BrainRepository` and add the brainId param + a paired resolver:
```java
// add imports: com.msfg.rag.domain.Brain, com.msfg.rag.repository.BrainRepository, java.util.UUID
private final BrainRepository brainRepository;

public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties,
                          RuntimeSettings settings, BrainRepository brainRepository) {
    // ...existing assignments...
    this.brainRepository = brainRepository;
    // ...existing default/fallback registration checks unchanged...
}

public RoutedResponse generate(AiRequest request, UUID brainId) {
    Brain brain = brainRepository.findById(brainId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
    ResolvedModel resolved = resolve(brain, request.purpose());

    AiModelProvider primary = providers.get(resolved.provider());
    if (primary == null) {
        log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                request.purpose() == AiRequest.Purpose.UTILITY ? "utility" : "answer",
                resolved.provider(), routing.defaultProvider());
        primary = providers.get(routing.defaultProvider());
    }
    try {
        return new RoutedResponse(primary.generate(request.withModel(resolved.model())), false);
    } catch (Exception primaryFailure) {
        log.error("Primary AI provider '{}' failed: {}", primary.getProviderName(), primaryFailure.getMessage());
        AiModelProvider fallback = providers.get(routing.fallbackProvider());
        if (fallback == null || fallback == primary) {
            throw primaryFailure;
        }
        log.warn("Falling back to provider '{}'", fallback.getProviderName());
        return new RoutedResponse(fallback.generate(request.withModel(null)), true);
    }
}

/** Paired (provider, model): the brain's column when set, else the global default — never mixing a model across providers. */
private ResolvedModel resolve(Brain brain, AiRequest.Purpose purpose) {
    boolean utility = purpose == AiRequest.Purpose.UTILITY;
    String brainProvider = utility ? brain.getUtilityProvider() : brain.getAnswerProvider();
    String brainModel = utility ? brain.getUtilityModel() : brain.getAnswerModel();
    if (brainProvider != null && !brainProvider.isBlank()) {
        return new ResolvedModel(brainProvider, brainModel);
    }
    return utility
            ? new ResolvedModel(settings.utilityProvider(), settings.utilityModel())
            : new ResolvedModel(settings.answerProvider(), settings.answerModel());
}

private record ResolvedModel(String provider, String model) {}
```
Remove the old `generate(AiRequest)` method (the brainId overload replaces it).

- [ ] **Step 4: Thread `brainId` into the two call sites**

`AskService.java:107-108`: `modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt), brainId)`.

`RerankerService.java`: change `rerank(String question, List<RetrievedChunk> candidates, int topK)` → `rerank(String question, List<RetrievedChunk> candidates, int topK, UUID brainId)` (import `java.util.UUID`); the `generate` call becomes `modelRouterService.generate(AiRequest.forUtility(prompt, 0.0, 800), brainId)`.

`RetrievalService.java:131`: `ranked = rerankerService.rerank(question, ranked, topK, brainId)` (`retrieve` already has `brainId`).

- [ ] **Step 5: Fix the affected tests**

Compiler + Mockito will flag: any `modelRouterService.generate(...)` mock stub/verify (e.g. in `AskServiceTest`/`AskServiceBrainTest`) now takes two args — update to `generate(any(), any())` / `argThat(...)` with the brain arg. Any `rerank(...)` call gains the `brainId` arg. `RerankerServiceTest` (tests `parseScores` directly) likely needs no logic change beyond a `rerank(...)` call-site arg if present. Pass the default brain id `00000000-0000-0000-0000-000000000001` (`TestBrains.DEFAULT_ID`) where a brain id is needed.

- [ ] **Step 6: Run → PASS**: `./gradlew test --tests "com.msfg.rag.service.ai.ModelRouterServiceTest"`, then the full suite `./gradlew test`.

- [ ] **Step 7: Commit**
```bash
git add -A && git commit -q -m "$(cat <<'EOF'
Phase 4a: brain-aware model routing

ModelRouterService.generate(req, brainId) resolves the brain's answer/utility
provider+model (paired) before the global RuntimeSettings default; brainId
threaded from AskService (answer) and RerankerService via RetrievalService
(utility). Activates the platform's intended ANSWER/UTILITY split per brain.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Regression + boot verification

- [ ] **Step 1:** `./gradlew test` → green (golden-pack + compliance + routing tests).
- [ ] **Step 2:** Boot on 8090 (background): confirm clean startup; an ask against the default brain returns a structured response (answer lane → anthropic). If you have corpus loaded, a non-refusal answer exercises the reranker on the utility lane (openai); on an empty corpus the refusal path is fine. Stop + `docker compose down`.
- [ ] **Step 3: Gate:** routing reads per-brain columns (default brain → answer=anthropic, utility=openai); full suite green; `git -C /Users/zacharyzink/MSFG/msfg-rag status --short` shows only `?? scripts/`.

---

## Self-Review

- **Spec coverage (§8 per-brain model):** `generate` resolves brain columns paired-then-global (Task 1 Step 3); `brainId` threaded from both lanes (Step 4). Default-brain answer behavior preserved (anthropic); utility activates the designed cheap lane (documented).
- **Placeholder scan:** none — full resolver code, exact call-site edits, test shape.
- **Consistency:** `generate(AiRequest, UUID)`; `rerank(..., UUID brainId)`; paired `(provider, model)`; `ResolvedModel` record; default brain id `00000000-0000-0000-0000-000000000001` in tests.
- **Invariant preserved:** a model name is only ever sent with its own provider (brain pair or global pair); the fallback path still uses `withModel(null)`.

## Notes — P4b (next)

P4b adds the **local-LLM provider stub**: `LOCAL_LLM_BASE_URL/API_KEY/MODEL` env → a conditional OpenAI-compatible provider bean in `ExtraProvidersConfig` registered under name `local`; a brain selecting `answer/utility_provider=local` with an optional per-brain `local_base_url`/`local_api_key_ref` override; and the **SSRF allowlist** (`LOCAL_LLM_ALLOWED_HOSTS`, block loopback/link-local/private at write+call time). The per-brain endpoint override is the one non-trivial piece (the OpenAI-compatible client is built with a fixed base-url today — P4b decides per-brain client construction). Then P5 source binding, P6 dashboard+security, P7 docs.
