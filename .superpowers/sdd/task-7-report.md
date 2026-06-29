Status: Complete

Implementation summary:
- Added active brain id to the admin stats payload and surfaced it in the dashboard shell so the active brain context is visible and reusable.
- Added dashboard profile types and API helpers for brain profile load/save and public token rotation.
- Added a new Personality screen for the active brain with dense settings controls for mode, behavior fields, public enablement, allowed domains, and token rotation.
- Extended the Test console with a public ask mode that calls the public endpoint, renders public response fields, and keeps the public security boundary intact by not exposing trace ids.
- Kept public origin handling production-safe by using the browser's real origin and rejecting mismatched typed values instead of attempting to spoof the `Origin` header.

Files changed:
- `src/main/java/com/msfg/rag/controller/AdminStatsController.java`
- `src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java`
- `dashboard/src/types.ts`
- `dashboard/src/api.ts`
- `dashboard/src/App.tsx`
- `dashboard/src/screens/Personality.tsx`
- `dashboard/src/screens/TestConsole.tsx`
- `dashboard/src/styles.css`

TDD red/green evidence:
- Red:
  - Command: `./gradlew test --tests com.msfg.rag.controller.AdminStatsControllerTest`
  - Result: FAIL at compile time with `cannot find symbol` for `stats.brain().id()` after adding the new assertion in `AdminStatsControllerTest`.
- Green:
  - Command: `./gradlew test --tests com.msfg.rag.controller.AdminStatsControllerTest`
  - Result: PASS after updating `AdminStatsController.BrainDto` to include the active brain UUID and returning it from the controller.

Verification commands/results:
- `./gradlew test --tests com.msfg.rag.controller.AdminStatsControllerTest`
  - PASS
- `cd dashboard && npm run build`
  - PASS
- `cd dashboard && npm run check`
  - PASS

Commit id:
- `63e9fb1`

Concerns:
- The public test mode intentionally does not let the dashboard fake a different `Origin` header. To test a different allowed domain, the dashboard must actually be loaded from that origin or the allowed-domain list must include the dashboard's real browser origin.
