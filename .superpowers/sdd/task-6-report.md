Status: NEEDS_CONTEXT

Blocking conflict:
- The task brief says, in Step 4, to "include `trace.getId()` in the `PublicAskResponse`."
- The task context for this run says "Task 4 also removed trace ids from public responses, so do not reintroduce public trace exposure."
- Current code matches the context, not the brief:
  - `src/main/java/com/msfg/rag/dto/PublicAskResponse.java` has no `traceId` field.
  - `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java` contains `publicAskResponseContractDoesNotExposeTraceId()`, which asserts the public response contract does not expose `traceId`.

Why this blocks exact implementation:
- Implementing the brief verbatim would require changing the public API contract and breaking the existing Task 4 invariant.
- Not implementing that part would mean deviating from the brief's exact instructions.

No code changes were made.
No tests were run.
No commit was created.

---

Resolution applied:
- The brief instruction to include `trace.getId()` in `PublicAskResponse` was superseded.
- Public endpoints still do not expose trace ids.

Implementation:
- Expanded `RagTraceService.record(...)` to capture response type, clarification decision, visibility filter, confidence reason, and validation outcome.
- Added `RagTraceService.recordPublicDecision(...)` for internal pre-retrieval public decision tracing.
- Updated `AskService` answer and refusal trace calls to pass the new metadata, preserving caller visibility on refusal paths.
- Updated `PublicAskService` to record an internal trace row for public `CLARIFY` responses without changing `PublicAskResponse`.
- Added coverage for public clarify trace recording and the expanded answer-trace metadata flow.
- Updated `AskServiceBrainTest` for the expanded trace method signature.

Verification:
- `./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest`
- `./gradlew test --tests com.msfg.rag.service.AskServiceBrainTest`

Result:
- Focused tests passed.

---

Status: DONE

Task 6 public navigate escalation mapping fix:
- `PublicAskService` now preserves the internal pre-retrieval `NAVIGATE` trace while mapping the final public response type from the downstream `AskResponse`.
- Public responses return `ESCALATE` when `humanEscalationRequired()` is true, otherwise `NAVIGATE`.
- Added a regression test for the `NAVIGATE` decision plus downstream escalation path.

Changed files:
- `src/main/java/com/msfg/rag/service/publicapi/PublicAskService.java`
- `src/test/java/com/msfg/rag/service/publicapi/PublicAskServiceTest.java`

Tests run:
- `./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest`

Results:
- PASS

Commit id:
- `c8538d5`

Concerns:
- None.

---

Status: DONE

Task 6 review follow-up fix:
- Refusal traces now record explicit escalation clarification metadata from `AskService.refuse(...)` instead of relying on the trace-service fallback.
- `RagTraceService` now sanitizes collected facts before freezing them, so null-valued public facts no longer blow up `recordPublicDecision(...)`.
- Added regression coverage for both the refusal trace metadata and null-safe public-decision fact handling.

Changed files:
- `src/main/java/com/msfg/rag/service/AskService.java`
- `src/main/java/com/msfg/rag/service/audit/RagTraceService.java`
- `src/test/java/com/msfg/rag/service/AskServiceTest.java`
- `src/test/java/com/msfg/rag/service/audit/RagTraceServiceTest.java`

Tests run:
- `./gradlew test --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.audit.RagTraceServiceTest`

Results:
- PASS

Commit id:
- `fe1a0c2`

Concerns:
- None.
- Commit created: `5dbfde9 Trace public ask decisions`

---

Status: DONE

Task 6 trace completeness fix:
- `RagTraceService.record(...)` now sets `collected_facts` explicitly for normal ask traces, defaulting to `{}` when callers have no facts to supply.
- `RagTraceService.recordPublicDecision(...)` now accepts supplied public facts, merges them with `session_id`, and persists the combined payload in `collected_facts`.
- `PublicAskService` now records internal public decision traces for both `CLARIFY` and `NAVIGATE` clarification outcomes before returning or continuing to answer.
- Public `NAVIGATE` still flows through `AskService` for the actual response generation, and public responses still do not expose trace ids.

Tests added/updated:
- `PublicAskServiceTest` covers public clarify trace invocation with empty facts, supplied-facts forwarding, public `NAVIGATE` decision tracing, and preserved no-public-trace-id behavior.
- `AskServiceTest` covers explicit empty `collected_facts` on normal answer traces.
- `AskServiceBrainTest` updated for the expanded trace method signature.

Verification:
- `./gradlew test --tests com.msfg.rag.service.publicapi.PublicAskServiceTest --tests com.msfg.rag.service.AskServiceTest --tests com.msfg.rag.service.AskServiceBrainTest`

Result:
- Focused tests passed.
