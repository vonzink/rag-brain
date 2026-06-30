# rag-brain Connectors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scoped connector clients so agents, server apps, and peer `rag-brain` instances can discover, ask, retrieve, and inspect readiness through a safe MCP-style/federation surface.

**Architecture:** Create a shared connector foundation with hashed connector tokens, scopes, and audit events. Expose it through admin-managed connector clients, a public discovery manifest, federation endpoints under `/api/connect/v1`, and an MCP-shaped HTTP facade under `/mcp/tools`. Keep website public tokens and admin keys separate.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring MVC, Spring Data JPA, Flyway, PostgreSQL JSONB, React/Vite/TypeScript, Vitest.

## Global Constraints

- Public website tokens remain separate from connector tokens.
- Connector tokens are stored only as SHA-256 hashes.
- Missing, invalid, or disabled connector credentials return `401`.
- Valid connector credentials with missing scope return `403`.
- Connector ask and retrieval use only `SourceVisibility.PUBLIC`.
- Connector responses must not expose trace IDs, internal source content, local paths, S3 bucket names, local model endpoints, or admin settings.
- Discovery manifest is public and contains no secrets.
- Admin mutation remains protected by `X-Admin-Api-Key`.
- No new external frontend dependencies.

---

## File Structure

- `src/main/resources/db/migration/V15__connector_clients.sql`: connector clients/events schema.
- `src/main/java/com/msfg/rag/domain/BrainConnectorClient.java`: JPA entity for connector credentials.
- `src/main/java/com/msfg/rag/domain/BrainConnectorEvent.java`: JPA entity for connector audit events.
- `src/main/java/com/msfg/rag/repository/BrainConnectorClientRepository.java`: connector lookup.
- `src/main/java/com/msfg/rag/repository/BrainConnectorEventRepository.java`: connector event persistence.
- `src/main/java/com/msfg/rag/service/connect/ConnectorScope.java`: allowed scope constants.
- `src/main/java/com/msfg/rag/service/connect/ConnectorAuthService.java`: token generation, hash, validation, scope enforcement, event writing.
- `src/main/java/com/msfg/rag/service/connect/ConnectorQueryService.java`: public-safe ask/retrieve behavior for connector callers after connector auth succeeds.
- `src/main/java/com/msfg/rag/controller/AdminConnectorController.java`: admin CRUD/rotate/enable surface.
- `src/main/java/com/msfg/rag/controller/ConnectManifestController.java`: `/.well-known/rag-brain.json`.
- `src/main/java/com/msfg/rag/controller/FederationController.java`: `/api/connect/v1` endpoints.
- `src/main/java/com/msfg/rag/controller/McpFacadeController.java`: `/mcp/tools` endpoint with MCP-shaped tool calls.
- `dashboard/src/screens/Connectors.tsx`: connector management UI.
- `dashboard/src/connect/connectorSnippets.ts`: copy snippets for MCP/federation/peer setup.
- `dashboard/src/api.ts`, `dashboard/src/types.ts`, `dashboard/src/App.tsx`: dashboard integration.
- `docs/website-integration.md`, `README.md`: connector docs.

## Task 1: Connector Schema, Domain, And Repositories

**Files:**
- Create: `src/main/resources/db/migration/V15__connector_clients.sql`
- Create: `src/main/java/com/msfg/rag/domain/BrainConnectorClient.java`
- Create: `src/main/java/com/msfg/rag/domain/BrainConnectorEvent.java`
- Create: `src/main/java/com/msfg/rag/repository/BrainConnectorClientRepository.java`
- Create: `src/main/java/com/msfg/rag/repository/BrainConnectorEventRepository.java`
- Test: `src/test/java/com/msfg/rag/repository/BrainConnectorRepositoryTest.java`

**Interfaces:**
- Produces: `BrainConnectorClient`, `BrainConnectorEvent`, `BrainConnectorClientRepository.findByTokenHash(String)`.

- [ ] **Step 1: Write the failing repository test**

Create `BrainConnectorRepositoryTest` that saves a connector with scopes and verifies lookup by token hash plus persisted event metadata:

```java
@Test
void connectorClientPersistsScopesAndEvents() {
    BrainConnectorClient client = new BrainConnectorClient(UUID.randomUUID(), "agent", "MCP_AGENT", "hash-1");
    client.setBrainId(TestBrains.DEFAULT_ID);
    client.setScopes(List.of("brains:list", "ask:public"));
    client.setEnabled(true);
    clients.saveAndFlush(client);

    BrainConnectorClient found = clients.findByTokenHash("hash-1").orElseThrow();
    assertEquals(List.of("brains:list", "ask:public"), found.getScopes());

    events.saveAndFlush(new BrainConnectorEvent(UUID.randomUUID(), found.getId(),
            TestBrains.DEFAULT_ID, "ASK", "ask:public", "example.com", "200"));

    assertEquals(1, events.findAll().size());
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.BrainConnectorRepositoryTest
```

Expected: compile failure because connector entities/repositories do not exist.

- [ ] **Step 3: Add Flyway schema**

Create `V15__connector_clients.sql`:

```sql
CREATE TABLE brain_connector_clients (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    type VARCHAR(40) NOT NULL,
    brain_id UUID REFERENCES brains(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_origins JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_peer_hosts JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connector_clients_brain ON brain_connector_clients(brain_id);
CREATE INDEX idx_connector_clients_enabled ON brain_connector_clients(enabled);

CREATE TABLE brain_connector_events (
    id UUID PRIMARY KEY,
    connector_client_id UUID REFERENCES brain_connector_clients(id),
    brain_id UUID REFERENCES brains(id),
    event_type VARCHAR(40) NOT NULL,
    scope VARCHAR(80),
    request_host VARCHAR(255),
    request_id VARCHAR(120),
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connector_events_client_created ON brain_connector_events(connector_client_id, created_at DESC);
CREATE INDEX idx_connector_events_brain_created ON brain_connector_events(brain_id, created_at DESC);
```

- [ ] **Step 4: Add entities and repositories**

Add JPA entities with JSONB `List<String>` fields using `@JdbcTypeCode(SqlTypes.JSON)`, `@PrePersist`, and `@PreUpdate`, matching `BrainProfile` patterns. Add repositories:

```java
public interface BrainConnectorClientRepository extends JpaRepository<BrainConnectorClient, UUID> {
    Optional<BrainConnectorClient> findByTokenHash(String tokenHash);
    List<BrainConnectorClient> findByBrainIdOrBrainIdIsNull(UUID brainId);
}
```

```java
public interface BrainConnectorEventRepository extends JpaRepository<BrainConnectorEvent, UUID> {
    List<BrainConnectorEvent> findTop25ByConnectorClientIdOrderByCreatedAtDesc(UUID connectorClientId);
}
```

- [ ] **Step 5: Run green test and commit**

Run:

```bash
./gradlew test --tests com.msfg.rag.repository.BrainConnectorRepositoryTest
git add src/main/resources/db/migration/V15__connector_clients.sql src/main/java/com/msfg/rag/domain/BrainConnectorClient.java src/main/java/com/msfg/rag/domain/BrainConnectorEvent.java src/main/java/com/msfg/rag/repository/BrainConnectorClientRepository.java src/main/java/com/msfg/rag/repository/BrainConnectorEventRepository.java src/test/java/com/msfg/rag/repository/BrainConnectorRepositoryTest.java
git commit -m "Add connector client schema"
```

## Task 2: Connector Auth Service And Admin API

**Files:**
- Create: `src/main/java/com/msfg/rag/service/connect/ConnectorScope.java`
- Create: `src/main/java/com/msfg/rag/service/connect/ConnectorAuthService.java`
- Create: `src/main/java/com/msfg/rag/dto/ConnectorClientDto.java`
- Create: `src/main/java/com/msfg/rag/dto/ConnectorClientRequest.java`
- Create: `src/main/java/com/msfg/rag/controller/AdminConnectorController.java`
- Test: `src/test/java/com/msfg/rag/service/connect/ConnectorAuthServiceTest.java`
- Test: `src/test/java/com/msfg/rag/controller/AdminConnectorControllerTest.java`

**Interfaces:**
- Produces: `ConnectorAuthService.rotateToken(UUID)`, `ConnectorAuthService.require(String, String, UUID, String, String)`.
- Produces admin routes under `/api/ai/admin/connectors`.

- [ ] **Step 1: Write failing auth tests**

Cover:

- `rotateToken` returns `rb_conn_` and stores only hash.
- missing/invalid token throws `ResponseStatusException` 401.
- missing scope throws `ResponseStatusException` 403.
- disabled client throws 401.

- [ ] **Step 2: Write failing admin controller tests**

Cover:

- create connector with scopes returns DTO with `hasToken=false`.
- rotate token returns one-time token and DTO has `hasToken=true`.
- disable connector sets `enabled=false`.

- [ ] **Step 3: Implement auth service**

Use SHA-256 token hashing like `PublicAccessService`. `require(...)` must strip `Bearer `, load by hash, enforce `enabled`, enforce optional `brainId` restriction, enforce scope, update `lastUsedAt`, and write connector events.

- [ ] **Step 4: Implement admin controller**

Expose:

- `GET /api/ai/admin/connectors`
- `POST /api/ai/admin/connectors`
- `PUT /api/ai/admin/connectors/{id}`
- `POST /api/ai/admin/connectors/{id}/token`
- `POST /api/ai/admin/connectors/{id}/enable`
- `POST /api/ai/admin/connectors/{id}/disable`
- `GET /api/ai/admin/connectors/{id}/events`

Never return `token_hash`; token value appears only in rotate-token response.

- [ ] **Step 5: Run tests and commit**

Run:

```bash
./gradlew test --tests com.msfg.rag.service.connect.ConnectorAuthServiceTest --tests com.msfg.rag.controller.AdminConnectorControllerTest
git add src/main/java/com/msfg/rag/service/connect src/main/java/com/msfg/rag/dto/ConnectorClientDto.java src/main/java/com/msfg/rag/dto/ConnectorClientRequest.java src/main/java/com/msfg/rag/controller/AdminConnectorController.java src/test/java/com/msfg/rag/service/connect/ConnectorAuthServiceTest.java src/test/java/com/msfg/rag/controller/AdminConnectorControllerTest.java
git commit -m "Add scoped connector administration"
```

## Task 3: Discovery Manifest And Federation Endpoints

**Files:**
- Create: `src/main/java/com/msfg/rag/controller/ConnectManifestController.java`
- Create: `src/main/java/com/msfg/rag/controller/FederationController.java`
- Create: `src/main/java/com/msfg/rag/dto/FederationDtos.java`
- Create: `src/main/java/com/msfg/rag/service/connect/ConnectorQueryService.java`
- Test: `src/test/java/com/msfg/rag/controller/ConnectManifestControllerTest.java`
- Test: `src/test/java/com/msfg/rag/controller/FederationControllerTest.java`
- Test: `src/test/java/com/msfg/rag/service/connect/ConnectorQueryServiceTest.java`

**Interfaces:**
- Consumes: `ConnectorAuthService.require(...)`, `AskService`, `ClarificationService`, `RetrievalService`.
- Produces: `GET /.well-known/rag-brain.json`, `/api/connect/v1/brains`, `/api/connect/v1/brains/{slug}/ask`, `/api/connect/v1/brains/{slug}/retrieve`, `/api/connect/v1/brains/{slug}/readiness`.

- [ ] **Step 1: Write failing manifest test**

Assert public manifest includes protocol `rag-brain-connect`, endpoints, capabilities, and active brain slugs, while excluding token/hash/admin/local/S3 fields.

- [ ] **Step 2: Write failing federation tests**

Use mocks for `ConnectorAuthService`, `BrainRepository`, `ConnectorQueryService`, and `BrainReadinessController`. Cover:

- missing ask scope maps to thrown `ResponseStatusException`.
- ask calls `ConnectorQueryService.ask(brain, request)` after connector auth.
- retrieve calls `ConnectorQueryService.retrieve(brain, request)` after connector auth.

- [ ] **Step 3: Implement manifest**

Manifest response includes only:

- protocol
- version
- instance name
- active brains `{slug, displayName, active}`
- endpoints `{federation, mcp}`
- capabilities

- [ ] **Step 4: Implement connector query service**

`ConnectorQueryService.ask(Brain brain, FederationDtos.FederationAskRequest req)` mirrors `PublicAskService` response mapping without calling `PublicAccessService`. It uses `ClarificationService` with `SourceVisibility.PUBLIC.name()`, calls `AskService.ask(..., SourceVisibility.PUBLIC)`, and returns `PublicAskResponse` without trace IDs.

`ConnectorQueryService.retrieve(Brain brain, FederationDtos.FederationRetrieveRequest req)` calls `RetrievalService.retrieve(req.question(), brain.getId(), SourceVisibility.PUBLIC)` and maps chunks to DTOs with `content`, `sourceName`, `documentName`, `section`, `pageNumber`, `combinedScore`, and `documentId`.

- [ ] **Step 5: Implement federation controller**

Use `Authorization: Bearer rb_conn_...`; derive token from header. For ask/retrieve, resolve brain by slug first, then call `ConnectorAuthService.require(token, scope, brainId, requestHost, eventType)`. Ask must return `PublicAskResponse`; retrieve must return public chunk DTOs with citation metadata and scores.

- [ ] **Step 6: Run tests and commit**

Run:

```bash
./gradlew test --tests com.msfg.rag.controller.ConnectManifestControllerTest --tests com.msfg.rag.controller.FederationControllerTest --tests com.msfg.rag.service.connect.ConnectorQueryServiceTest
git add src/main/java/com/msfg/rag/controller/ConnectManifestController.java src/main/java/com/msfg/rag/controller/FederationController.java src/main/java/com/msfg/rag/dto/FederationDtos.java src/main/java/com/msfg/rag/service/connect/ConnectorQueryService.java src/test/java/com/msfg/rag/controller/ConnectManifestControllerTest.java src/test/java/com/msfg/rag/controller/FederationControllerTest.java src/test/java/com/msfg/rag/service/connect/ConnectorQueryServiceTest.java
git commit -m "Expose connector discovery and federation APIs"
```

## Task 4: MCP-Shaped Facade

**Files:**
- Create: `src/main/java/com/msfg/rag/controller/McpFacadeController.java`
- Test: `src/test/java/com/msfg/rag/controller/McpFacadeControllerTest.java`

**Interfaces:**
- Consumes: federation services and connector auth.
- Produces: `GET /mcp/tools`, `POST /mcp/tools/{toolName}`.

- [ ] **Step 1: Write failing tests**

Cover:

- tool list includes `rag_brain_list_brains`, `rag_brain_readiness`, `rag_brain_ask`, `rag_brain_retrieve`.
- `rag_brain_ask` delegates to federation ask behavior and enforces `ask:public`.
- unknown tool returns 404.

- [ ] **Step 2: Implement minimal HTTP facade**

The first version is MCP-shaped JSON over HTTP:

```json
{
  "name": "rag_brain_ask",
  "description": "Ask a public-safe question against a rag-brain brain",
  "inputSchema": {
    "type": "object",
    "required": ["slug", "message"],
    "properties": {
      "slug": {"type": "string"},
      "message": {"type": "string"}
    }
  }
}
```

- [ ] **Step 3: Run tests and commit**

Run:

```bash
./gradlew test --tests com.msfg.rag.controller.McpFacadeControllerTest
git add src/main/java/com/msfg/rag/controller/McpFacadeController.java src/test/java/com/msfg/rag/controller/McpFacadeControllerTest.java
git commit -m "Add MCP-shaped connector facade"
```

## Task 5: Dashboard Connectors Screen

**Files:**
- Create: `dashboard/src/connect/connectorSnippets.ts`
- Create: `dashboard/src/connect/connectorSnippets.test.ts`
- Create: `dashboard/src/screens/Connectors.tsx`
- Modify: `dashboard/src/api.ts`
- Modify: `dashboard/src/types.ts`
- Modify: `dashboard/src/App.tsx`
- Modify: `dashboard/src/styles.css`

**Interfaces:**
- Consumes: admin connector API.
- Produces: nav item `Connectors`, list/create/rotate/disable UI, snippet builders.

- [ ] **Step 1: Write failing snippet tests**

Cover snippets for:

- MCP tool config.
- federation curl ask.
- peer manifest registration.

- [ ] **Step 2: Add types and API client**

Add `ConnectorClient`, `ConnectorClientRequest`, `ConnectorTokenResponse`, and `ConnectorEvent` to `types.ts`. Add `connectorsApi` to `api.ts`.

- [ ] **Step 3: Build Connectors screen**

Use existing dashboard patterns:

- brain dropdown
- connector name/type/scopes controls
- table of clients
- rotate-token button with one-time token warning
- enable/disable button
- snippet panel

- [ ] **Step 4: Register route and styles**

Add sidebar link and route in `App.tsx`; keep styling consistent with existing cards/tables.

- [ ] **Step 5: Run dashboard tests and commit**

Run:

```bash
cd dashboard && npm run test -- --run src/connect/connectorSnippets.test.ts
cd dashboard && npm run check
git add dashboard/src/connect/connectorSnippets.ts dashboard/src/connect/connectorSnippets.test.ts dashboard/src/screens/Connectors.tsx dashboard/src/api.ts dashboard/src/types.ts dashboard/src/App.tsx dashboard/src/styles.css
git commit -m "Add connector management dashboard"
```

## Task 6: Documentation And Full Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/website-integration.md`

**Interfaces:**
- Produces docs for manifest, connector tokens, federation calls, and MCP-shaped facade.

- [ ] **Step 1: Document connector setup**

Add a `Connector Clients` section to `README.md` with:

- create connector in dashboard
- rotate token
- call manifest
- call federation ask
- use MCP-shaped tool list

- [ ] **Step 2: Document website-token separation**

Update `docs/website-integration.md` to explicitly state that website widget tokens are not connector tokens.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew test
cd dashboard && npm run check
cd dashboard && npm run test -- --run
cd dashboard && npm run build
git diff --check
```

- [ ] **Step 4: Commit docs**

```bash
git add README.md docs/website-integration.md
git commit -m "Document rag-brain connector clients"
```

## Self-Review

- Spec coverage: Tasks 1-6 cover connector schema, tokens/scopes, admin management, manifest, federation API, MCP-shaped facade, dashboard screen, audit events, and docs.
- Placeholder scan: no unresolved placeholder markers or open-ended implementation placeholders are intentionally left in the executable task list.
- Type consistency: Java package names use `com.msfg.rag.service.connect`, controller paths match the design, and dashboard API names follow existing `brainsApi`/`profileApi` patterns.
