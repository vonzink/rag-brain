# Task 5 Report: Source Visibility Enforcement In Retrieval

## Status
DONE

## Context

Task 5 was implemented during Task 4 hardening after review identified that the newly added public ask endpoint would be unsafe without source visibility enforcement. The relevant work is in these commits:

- `cba73a2 Enforce public boundaries for ask endpoint`
- `0a26b61 Harden legacy ask and admin retrieval visibility`

## What changed

- `DocumentChunkRepository` vector and keyword searches now filter by source visibility and exclude blocked trust level sources for public visibility-limited retrieval.
- `RetrievalService` now supports explicit visibility-aware retrieval.
- `RetrievalService` also exposes an admin retrieval path so admin retrieval inspection can still see internal material when needed.
- `AskService` accepts an explicit `SourceVisibility` and passes it into retrieval before prompt assembly.
- `PublicAskService` calls `AskService` with `SourceVisibility.PUBLIC`.
- `DocumentAdminController.testRetrieval(...)` uses the admin retrieval path to preserve admin/dashboard inspection behavior.
- `HybridSearchIntegrationTest`, `AskServiceTest`, `PublicAskServiceTest`, and `DocumentAdminControllerRetrievalTest` cover the public/admin visibility boundary.

## Verification

Reported test command from the implementing fix:

```bash
./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest \
  --tests com.msfg.rag.service.AskServiceTest \
  --tests com.msfg.rag.repository.HybridSearchIntegrationTest \
  --tests com.msfg.rag.config.RateLimitFilterTest \
  --tests com.msfg.rag.controller.AskControllerTest \
  --tests com.msfg.rag.controller.DocumentAdminControllerRetrievalTest
```

Result: PASS.

## Concerns

None for Task 5. The Task 4 final review noted a minor API-cleanliness concern that `PublicAskRequest.surface` is accepted but ignored by the public endpoint. That is safe and tracked for final review.

---

## 2026-06-28 Addendum: Admin retrieval preservation

### Status
DONE

### What changed

- Split repository retrieval into public methods and admin methods.
- Public `searchByKeyword` and `searchByVector` now require an explicit visibility match and still exclude `BLOCKED` sources.
- Admin `searchByKeywordAdmin` and `searchByVectorAdmin` preserve the optional visibility filter but do not exclude `BLOCKED` sources.
- `RetrievalService.retrieve(...)` now uses the public repository path, while `retrieveAdmin(...)` uses the admin-safe path.

### Verification

Ran:

```bash
./gradlew test --tests com.msfg.rag.repository.HybridSearchIntegrationTest \
  --tests com.msfg.rag.controller.DocumentAdminControllerRetrievalTest \
  --tests com.msfg.rag.service.publicapi.PublicAskServiceTest \
  --tests com.msfg.rag.service.AskServiceTest
```

Result: PASS.

### Coverage added

- Public retrieval still excludes `INTERNAL` and `BLOCKED` documents.
- Admin retrieval without a visibility filter can still inspect internal documents.
- Admin retrieval can now inspect `BLOCKED` documents, including when filtered to `PUBLIC` visibility.
