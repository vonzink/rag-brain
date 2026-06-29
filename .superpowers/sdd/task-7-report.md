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
- `aa909d7`

Concerns:
- The public test mode intentionally does not let the dashboard fake a different `Origin` header. To test a different allowed domain, the dashboard must actually be loaded from that origin or the allowed-domain list must include the dashboard's real browser origin.

---

Fix follow-up:

Status:
- Complete

Changed files:
- `dashboard/package.json`
- `dashboard/package-lock.json`
- `dashboard/vite.config.ts`
- `dashboard/src/test/setup.ts`
- `dashboard/src/api.ts`
- `dashboard/src/types.ts`
- `dashboard/src/screens/Personality.tsx`
- `dashboard/src/screens/TestConsole.tsx`
- `dashboard/src/screens/Personality.test.tsx`
- `dashboard/src/screens/TestConsole.test.tsx`

TDD red/green evidence:
- Red:
  - Command: `cd dashboard && npm run test -- --run src/screens/Personality.test.tsx src/screens/TestConsole.test.tsx`
  - Result: FAIL
  - Evidence:
    - `Personality > keeps the form locked after a profile load failure` failed because `Save profile` was still rendered after `profileApi.get` rejected.
    - `TestConsole > renders public clarify responses and shows the effective origin as read-only` failed because the origin field was editable (`readOnly` was `false`).
- Green:
  - Command: `cd dashboard && npm run test -- --run src/screens/Personality.test.tsx src/screens/TestConsole.test.tsx`
  - Result: PASS (`4 passed`)

Verification commands/results:
- `cd dashboard && npm run test -- --run`
  - PASS (`3 files, 8 tests`)
- `cd dashboard && npm run build`
  - PASS
- `cd dashboard && npm run check`
  - PASS

Commit id:
- `fc12d6b`

Concerns:
- The dashboard now only unlocks the Personality form for a recognized `404` uninitialized-profile response. The current backend still returns a created profile on successful GET, so this path remains defensive rather than actively used.
