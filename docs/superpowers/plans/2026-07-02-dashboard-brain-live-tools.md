# Dashboard Brain Live Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add internal dashboard-agent tool contracts, scoped connector endpoints, confirmation-gated write stubs, and MCP-style facade entries.

**Architecture:** Build a small `service/dashboard` module beside existing connector services. `FederationController` remains public-safe; new `DashboardAgentController` handles internal dashboard endpoints with explicit connector scopes. `McpFacadeController` delegates new MCP-style dashboard tool names to the dashboard controller.

**Tech Stack:** Java 21, Spring Boot, Spring MVC controllers, existing connector auth, JUnit 5, Mockito, MockMvc, Flyway-free first slice using existing connector event tables.

## Global Constraints

- Browser clients must not receive dashboard connector tokens.
- All dashboard endpoints require `rb_conn_...` connector auth.
- Internal dashboard scopes are separate from public connector scopes.
- Write tools must require explicit confirmation before execution.
- First implementation must stub live dashboard API execution; it must not mutate external data.
- Tool calls must return stable contracts that a dashboard backend can wire against later.
- MCP support is a lightweight HTTP facade, matching the existing `/mcp/tools` pattern.

---

### Task 1: Internal Connector Scopes

**Files:**
- Modify: `src/main/java/com/ragbrain/rag/service/connect/ConnectorScope.java`
- Test: `src/test/java/com/ragbrain/rag/controller/AdminConnectorControllerTest.java`

**Interfaces:**
- Produces constants: `DASHBOARD_ASK`, `DASHBOARD_TOOLS_LIST`, `DASHBOARD_TOOLS_READ`, `DASHBOARD_TOOLS_WRITE`.
- Produces `MVP_SCOPES` containing the new strings so admin connector creation accepts them.

- [x] **Step 1: Write failing validation test**

Add a test that creates or validates a connector request with the four dashboard scopes and expects no rejection.

- [x] **Step 2: Run targeted test to verify failure**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.AdminConnectorControllerTest'`
Expected before implementation: failure because the scopes are not in `ConnectorScope.MVP_SCOPES`.

- [x] **Step 3: Implement scope constants**

Add the four constants and include them in `MVP_SCOPES`.

- [x] **Step 4: Verify targeted test passes**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.AdminConnectorControllerTest'`

---

### Task 2: Dashboard Tool DTOs and Registry

**Files:**
- Create: `src/main/java/com/ragbrain/rag/dto/DashboardAgentDtos.java`
- Create: `src/main/java/com/ragbrain/rag/service/dashboard/DashboardToolMode.java`
- Create: `src/main/java/com/ragbrain/rag/service/dashboard/DashboardToolStatus.java`
- Create: `src/main/java/com/ragbrain/rag/service/dashboard/DashboardToolRegistry.java`
- Test: `src/test/java/com/ragbrain/rag/service/dashboard/DashboardToolRegistryTest.java`

**Interfaces:**
- `DashboardToolRegistry.list()` returns built-in tool definitions.
- `DashboardToolRegistry.require(String name)` returns a definition or throws `IllegalArgumentException`.

- [x] **Step 1: Write failing registry tests**

Test that registry includes read, write, and navigation tools with expected permissions and confirmation behavior.

- [x] **Step 2: Run targeted test to verify failure**

Run: `./gradlew test --tests 'com.ragbrain.rag.service.dashboard.DashboardToolRegistryTest'`

- [x] **Step 3: Implement DTOs, enums, and registry**

Create DTO records for user context, tool definition, tool call request/response, and dashboard ask request/response. Create built-in definitions for search/read/write/navigation tools.

- [x] **Step 4: Verify targeted test passes**

Run: `./gradlew test --tests 'com.ragbrain.rag.service.dashboard.DashboardToolRegistryTest'`

---

### Task 3: Dashboard Tool Gateway

**Files:**
- Create: `src/main/java/com/ragbrain/rag/service/dashboard/DashboardToolGatewayService.java`
- Test: `src/test/java/com/ragbrain/rag/service/dashboard/DashboardToolGatewayServiceTest.java`

**Interfaces:**
- `DashboardToolGatewayService.call(String toolName, DashboardToolCallRequest request)` returns `DashboardToolCallResponse`.
- Throws `ResponseStatusException(HttpStatus.FORBIDDEN)` when user permissions do not contain required permissions.
- Returns `CONFIRMATION_REQUIRED` for unconfirmed write tools.
- Returns `STUBBED` for valid read tools and confirmed write tools until a real adapter exists.
- Returns `SUCCEEDED` for `navigateToRoute`.

- [x] **Step 1: Write failing gateway tests**

Test permission denial, read stub, write confirmation, confirmed write stub, and navigation success.

- [x] **Step 2: Run targeted test to verify failure**

Run: `./gradlew test --tests 'com.ragbrain.rag.service.dashboard.DashboardToolGatewayServiceTest'`

- [x] **Step 3: Implement gateway**

Implement permission checks, confirmation IDs, stub responses, and navigation hints.

- [x] **Step 4: Verify targeted test passes**

Run: `./gradlew test --tests 'com.ragbrain.rag.service.dashboard.DashboardToolGatewayServiceTest'`

---

### Task 4: Dashboard Connector Controller

**Files:**
- Create: `src/main/java/com/ragbrain/rag/controller/DashboardAgentController.java`
- Create: `src/main/java/com/ragbrain/rag/service/dashboard/DashboardAgentService.java`
- Test: `src/test/java/com/ragbrain/rag/controller/DashboardAgentControllerTest.java`

**Interfaces:**
- `GET /api/connect/v1/brains/{slug}/dashboard/tools`
- `POST /api/connect/v1/brains/{slug}/dashboard/tools/{toolName}/call`
- `POST /api/connect/v1/brains/{slug}/dashboard/ask`

- [x] **Step 1: Write failing controller tests**

Test that list/read/write/ask endpoints require the new scopes and delegate to services.

- [x] **Step 2: Run targeted test to verify failure**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.DashboardAgentControllerTest'`

- [x] **Step 3: Implement controller and ask service**

Resolve the brain by slug, require connector scopes, call the registry/gateway, and call `AskService` using `SourceVisibility.INTERNAL` by default or `SECURE` when requested.

- [x] **Step 4: Verify targeted test passes**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.DashboardAgentControllerTest'`

---

### Task 5: MCP Facade Stubout

**Files:**
- Modify: `src/main/java/com/ragbrain/rag/controller/McpFacadeController.java`
- Test: `src/test/java/com/ragbrain/rag/controller/McpFacadeControllerTest.java`

**Interfaces:**
- Add tools: `rag_brain_dashboard_tools`, `rag_brain_dashboard_tool_call`, `rag_brain_dashboard_ask`.
- Delegate to `DashboardAgentController` with the same authorization, host, and origin headers.

- [x] **Step 1: Write failing MCP tests**

Test the tool list includes dashboard tool names and a dashboard tool call delegates correctly.

- [x] **Step 2: Run targeted test to verify failure**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.McpFacadeControllerTest'`

- [x] **Step 3: Implement MCP facade entries**

Inject `DashboardAgentController`, add tool definitions, parse request maps, and delegate.

- [x] **Step 4: Verify targeted test passes**

Run: `./gradlew test --tests 'com.ragbrain.rag.controller.McpFacadeControllerTest'`

---

### Task 6: Docs and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/SETUP.md`
- Modify: `docs/website-integration.md`

**Interfaces:**
- Document the internal dashboard integration, connector scopes, tool call flow, and MCP-style stub names.

- [x] **Step 1: Update docs**

Add server-to-server dashboard integration examples and clarify that live data tools are stubbed until wired to the host dashboard API.

- [x] **Step 2: Run full backend tests**

Run: `./gradlew test`

- [x] **Step 3: Check git status**

Run: `git status --short --branch`
