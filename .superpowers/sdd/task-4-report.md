## Task 4 Report: Clarification Engine And Public Ask Contract

### Scope completed
- Added public ask DTO contract:
  - `PublicAskRequest`
  - `PublicAskResponse`
  - `PublicRecommendedPageDto`
  - `ClarificationQuestionDto`
- Added clarification decision flow:
  - `ClarificationDecision`
  - `ClarificationService`
- Added public ask execution path:
  - `PublicAskService`
  - `PublicAskController`
- Added focused tests:
  - `ClarificationServiceTest`
  - `PublicAskServiceTest`

### TDD evidence
1. Added the two test classes from the task brief before production code.
2. Ran:
   ```bash
   ./gradlew test --tests com.msfg.rag.service.clarification.ClarificationServiceTest --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
   ```
3. Observed expected red state: compilation failed because `ClarificationService`, `ClarificationDecision`, `PublicAskRequest`, and `PublicAskService` did not exist.
4. Implemented the production files listed in the brief.
5. Re-ran the same focused command and got `BUILD SUCCESSFUL`.

### Behavior implemented
- `ClarificationService.decide(...)`
  - Returns `NAVIGATE` for explicit page-finding language.
  - Evaluates active clarification rules by priority for the supplied brain.
  - For public requests, skips rules not required for public use.
  - Returns `CLARIFY` with the first missing required fact and configured question.
  - Returns `ANSWER` when no clarification is required.
- `PublicAskService.ask(...)`
  - Resolves the brain by slug.
  - Validates the public token and origin using `PublicAccessService`.
  - Runs clarification before calling `AskService`.
  - Short-circuits `CLARIFY` responses without invoking `AskService`.
  - Maps `AskService` responses into the public response contract for `ANSWER`, `NAVIGATE`, and `ESCALATE`.
- `PublicAskController`
  - Exposes `POST /api/ai/public/{slug}/ask`
  - Requires `X-Public-Brain-Token` and `Origin` headers.

### Verification run
```bash
./gradlew test --tests com.msfg.rag.service.clarification.ClarificationServiceTest --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
```

Result: `BUILD SUCCESSFUL`

---

## Task 4 Re-Review Fix Report: Public Ask Rate-Limit Pattern

### Finding addressed
- Updated `RateLimitFilter` so `/api/ai/public/{slug}/ask` is rate-limited for any slug, not just the configured `brain.slug`.
- Expanded `RateLimitFilterTest` to prove both `/api/ai/public/generic/ask` and `/api/ai/public/other-brain/ask` are gated by the filter.

### Verification run
```bash
./gradlew test --tests com.msfg.rag.config.RateLimitFilterTest
```

Result: `BUILD SUCCESSFUL`

---

## Task 4 Re-Review Fix Report: Legacy Ask Auth And Admin Retrieval

### Findings addressed
- Closed the legacy `/api/ai/{slug}/ask` unauthenticated path.
- Preserved legacy admin/test-console access by allowing a valid `X-Admin-Api-Key` to call the route with admin-selected visibility.
- Forced non-admin legacy callers through public token + origin validation and pinned them to `SourceVisibility.PUBLIC`.
- Restored admin retrieval inspection of INTERNAL material through an admin retrieval path that can run without a visibility filter or with an explicit admin-selected visibility.
- Extended rate limiting to the new `/api/ai/public/{slug}/ask` endpoint.

### TDD evidence
1. Added failing tests first in:
   - `src/test/java/com/msfg/rag/controller/AskControllerTest.java`
   - `src/test/java/com/msfg/rag/controller/DocumentAdminControllerRetrievalTest.java`
   - `src/test/java/com/msfg/rag/config/RateLimitFilterTest.java`
   - `src/test/java/com/msfg/rag/repository/HybridSearchIntegrationTest.java`
2. Ran:
   ```bash
   ./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.repository.HybridSearchIntegrationTest --tests com.msfg.rag.config.RateLimitFilterTest --tests com.msfg.rag.controller.AskControllerTest --tests com.msfg.rag.controller.DocumentAdminControllerRetrievalTest
   ```
3. Observed expected red state: test compilation failed because `AskController` did not yet branch on admin/public auth and `RetrievalService`/`DocumentAdminController` did not yet expose the admin retrieval entrypoint.
4. Implemented the auth branch, admin retrieval path, nullable visibility filtering, and public-route rate limiting.
5. Re-ran the same command and got `BUILD SUCCESSFUL`.

### Behavior implemented
- `AskController`
  - Accepts `X-Admin-Api-Key`, `X-Public-Brain-Token`, and `Origin` on the legacy route.
  - Uses constant-time admin-key comparison.
  - Allows admin callers to continue using the legacy route with explicit admin visibility.
  - Forces non-admin callers through `PublicAccessService.validate(...)`.
  - Rewrites the legacy public request surface to `PUBLIC` before calling `AskService`.
- `RetrievalService`
  - Added `retrieveAdmin(String question, UUID brainId, SourceVisibility visibility)`.
  - Allows admin retrieval to run without a visibility filter when no visibility is selected.
- `DocumentAdminController`
  - Updated `testRetrieval(...)` to use the admin retrieval path.
  - Added optional `visibility` selection for admin inspection.
- `DocumentChunkRepository`
  - Updated keyword/vector SQL to treat `visibility = null` as admin-wide retrieval instead of forcing `PUBLIC`.
- `RateLimitFilter`
  - Now rate-limits both `/api/ai/{slug}/ask` and `/api/ai/public/{slug}/ask`.

### Verification run
```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.repository.HybridSearchIntegrationTest --tests com.msfg.rag.config.RateLimitFilterTest --tests com.msfg.rag.controller.AskControllerTest --tests com.msfg.rag.controller.DocumentAdminControllerRetrievalTest
```

Result: `BUILD SUCCESSFUL`

### Self-review
- Kept changes scoped to the ten Task 4 files from the brief plus this report.
- Verified the implementation matches the exact DTO/service/controller shapes specified in the requirements document.
- Confirmed the new public ask path reuses the existing `AskService` path instead of duplicating answer-generation logic.

### Commit
- Planned commit message: `Add public ask clarification contract`

---

## Task 4 Review Fix Report: Public Boundary Enforcement

### Findings addressed
- Enforced `PUBLIC` source visibility before prompt assembly by threading visibility through `AskService`, `RetrievalService`, and `DocumentChunkRepository`.
- Forced the public endpoint surface to `PUBLIC` inside `PublicAskService`, ignoring caller-supplied `req.surface()`.
- Added focused tests for the forced-public service path and repository-level visibility/trust filtering.

### TDD evidence
1. Added failing tests first in:
   - `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`
   - `src/test/java/com/msfg/rag/service/AskServiceTest.java`
   - `src/test/java/com/msfg/rag/repository/HybridSearchIntegrationTest.java`
2. Ran:
   ```bash
   ./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.repository.HybridSearchIntegrationTest
   ```
3. Observed expected red state: test compilation failed because the new visibility-aware overloads and repository signatures did not exist.
4. Implemented the visibility boundary in the public/service/repository path.
5. Re-ran the same command and got `BUILD SUCCESSFUL`.

### Behavior implemented
- `PublicAskService`
  - Always uses surface `PUBLIC` for clarification and ask handoff.
  - Always calls `AskService.ask(..., SourceVisibility.PUBLIC)` for public requests.
- `AskService`
  - Added `ask(AskRequest request, UUID brainId, SourceVisibility visibility)`.
  - Kept the existing two-argument method as a delegating wrapper to `PUBLIC`.
- `RetrievalService`
  - Added `retrieve(String question, UUID brainId, SourceVisibility visibility)`.
  - Kept the existing two-argument method as a delegating wrapper to `PUBLIC`.
- `DocumentChunkRepository`
  - Added visibility parameter to vector and keyword search queries.
  - Enforced `d.visibility = :visibility`.
  - Enforced `d.trust_level <> 'BLOCKED'`.

### Verification run
```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.repository.HybridSearchIntegrationTest
```

Result: `BUILD SUCCESSFUL`

---

## Task 4 Re-Review Fix Report: Public Trace Leakage

### Finding addressed
- Removed `traceId` from `PublicAskResponse` so the public ask contract no longer exposes trace identifiers.
- Stopped copying `AskResponse.traceId()` into the public response mapping in `PublicAskService`.
- Added a regression test that inspects the public record components and fails if `traceId` returns.

### Verification run
```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest
```

Result: `BUILD SUCCESSFUL`
