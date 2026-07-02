# Dashboard Brain Live Tools Design

## Goal

Add an internal dashboard-agent surface so a cloned brain such as `dashboard-brain`
can answer from RAG knowledge, inspect changing dashboard data through approved
live-data tools, and request write actions that stay within the permissions and
routes of the host dashboard.

This feature belongs to `rag-brain` itself. It does not create a dashboard
application or bind the platform to one customer site. `dashboard-brain` is an
example clone/brain slug for a future internal dashboard integration; the same
internal-tool pattern should support other secure app-specific brains.

## Current Foundation

rag-brain already has connector clients, hashed `rb_conn_...` bearer tokens,
connector scopes, peer host and origin allowlists, public-safe federation
endpoints, an MCP-shaped HTTP facade, page guides, source links, RAG traces, and
recommended navigation output. The current connector API is intentionally
public-safe and retrieves only public source content, so it should not be reused
unchanged for internal user-specific dashboard data.

## Selected Approach

Use an internal server-to-server Dashboard Tool Gateway pattern first, with MCP
compatibility exposed as a thin tool facade. The dashboard backend calls
rag-brain using connector credentials and passes the authenticated user's role,
permission, tenant, page route, and selected facts. rag-brain can list available
tools, ask an internal/secure question, and call a read/write/navigation tool.

Actual dashboard API execution remains outside rag-brain in the first version.
The new gateway returns stubbed read/write responses with stable contracts and
audit events. This makes the integration safe to wire before the dashboard API
schema is available. Later, a concrete dashboard adapter can replace the stub
without changing clients.

## Security Model

- Browser clients do not receive dashboard connector tokens.
- The dashboard backend is the connector caller and remains responsible for user
  authentication, tenant resolution, and dashboard permission checks.
- Connector clients need explicit internal scopes:
  - `dashboard:ask`
  - `dashboard:tools:list`
  - `dashboard:tools:read`
  - `dashboard:tools:write`
- Tool calls include a user context with `userId`, `tenantId`, `roles`, and
  `permissions`.
- Write tools are confirmation-required. A write call without confirmation
  returns `CONFIRMATION_REQUIRED` and does not execute.
- Stubbed execution never mutates data. A future real adapter must keep the same
  confirmation and permission checks before calling dashboard APIs.
- Connector events record list/read/write/ask attempts and status. Raw connector
  tokens and sensitive payloads are not stored.

## Tool Model

Tools are grouped by mode:

- `READ`: live data lookups such as `searchLoans`, `getLoanSummary`,
  `listOpenTasks`, `getCalendarEvents`, `getDocumentChecklist`, and
  `getIntegrationStatus`.
- `WRITE`: controlled actions such as `createTask`, `updateTaskStatus`,
  `addLoanNote`, `scheduleFollowUp`, `markDocumentReviewed`, and
  `sendInternalMessage`.
- `NAVIGATE`: site-contained navigation such as `navigateToRoute`.

Each tool definition includes name, description, mode, confirmation requirement,
required permission strings, and a JSON-schema-shaped input contract. The first
implementation ships a built-in registry; future work can load tool definitions
from database rows or a dashboard manifest.

## API Contract

New internal connector endpoints live under `/api/connect/v1/brains/{slug}/dashboard`:

- `GET /tools`: list internal dashboard tool definitions.
- `POST /tools/{toolName}/call`: call one approved tool.
- `POST /ask`: ask the brain using `SourceVisibility.INTERNAL` or
  `SourceVisibility.SECURE`, with user/page context available as prompt facts.

The MCP facade exposes matching tool names:

- `rag_brain_dashboard_tools`
- `rag_brain_dashboard_tool_call`
- `rag_brain_dashboard_ask`

These are HTTP JSON facade tools, not a full streaming MCP transport. They keep
the current MCP-style pattern and give external tool clients stable names while
the server-to-server API remains the primary production integration.

## Response Contract

Tool call responses use these statuses:

- `SUCCEEDED`: the gateway completed a local non-mutating action such as a route
  navigation response.
- `CONFIRMATION_REQUIRED`: a write tool needs explicit user confirmation before
  any action may execute.
- `STUBBED`: the tool is valid and authorized, but no dashboard API adapter is
  configured yet.

Responses include `toolName`, `mode`, `message`, optional `confirmationId`, a
small `data` object, and optional `navigationHints`.

## Testing Requirements

Tests must prove:

- internal connector scopes are recognized by admin validation,
- tool listing requires `dashboard:tools:list`,
- read tools require `dashboard:tools:read`,
- write tools require `dashboard:tools:write`,
- write calls without confirmation return `CONFIRMATION_REQUIRED`,
- confirmed write calls are still `STUBBED` until an adapter exists,
- MCP facade exposes and delegates the dashboard tool names,
- dashboard ask uses an internal/secure visibility path rather than the existing
  public-safe federation path.

## Deferred Work

- Real dashboard API adapter implementation.
- Database-backed or dashboard-manifest-backed tool registry.
- Fine-grained policy expressions beyond simple permission strings.
- Streaming MCP transport.
- UI controls for configuring dashboard tool manifests.
