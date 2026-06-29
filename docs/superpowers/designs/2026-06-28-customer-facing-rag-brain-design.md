# rag-brain — Customer-Facing RAG Brain Platform Design

- **Date:** 2026-06-28
- **Status:** Written spec — awaiting user review
- **Project:** `/Users/zacharyzink/rag-brain`
- **Design target:** top-tier reusable RAG platform for public website assistants, private website assistants, and secure deployments
- **Build posture:** extend the existing multi-brain Spring Boot + React + PostgreSQL/pgvector platform; do not hard-code mortgage/MSFG behavior into the generic platform

## 1. Summary

`rag-brain` should become a reusable customer-facing RAG platform, not just an admin test console over documents. The first product target is a public website assistant that can run in a hero-page chat experience: it answers questions conversationally, recommends relevant website pages, asks focused clarifying questions when needed, and stays grounded in approved sources.

The platform must also support secure/private use cases. Public and secure assistants share the same core RAG engine, but each brain explicitly defines its profile, allowed source visibility, source trust policy, deployment mode, model routing, and public/private access surface.

The recommended first implementation path is **Option A: RAG Platform Foundation First**:

- Brain profiles for personality, audience, confidence target, response style, and escalation behavior.
- Managed learning sources for documents, URLs, domains, sitemaps, manual notes, and source versions.
- A public assistant API/contract for website widgets.
- Clarification-aware response routing.
- Source visibility enforcement before retrieval context reaches the model.
- URL learning MVP with preview, snapshots, trust levels, visibility, and manual ingest.
- Dashboard workflows for personality, learning sources, URL learning, test console, and trace exploration.

## 2. Goals

- Build a customer-facing assistant suitable for public website hero pages.
- Keep answers conversational instead of producing data dumps.
- Let a brain ask one useful clarifying question at a time when missing facts prevent high-confidence answers.
- Let a brain recommend website pages and navigation next steps.
- Support underwriting, loan strategy, guideline, product, and website-navigation questions through configurable topic rules, not mortgage-only code.
- Make source learning controlled, citeable, auditable, and versioned.
- Support both open/public websites and secure/private websites through explicit modes and visibility rules.
- Preserve a generic default platform that can be reused across many domains.

## 3. Non-Goals For V1

- No autonomous open-web browsing in answer mode.
- No scheduled recrawls yet; URL learning starts as admin-triggered.
- No CRM/GHL handoff beyond a structured `nextAction` or human-handoff signal.
- No full SSO/RBAC/account billing system yet.
- No per-customer SaaS tenancy guarantees beyond the existing brain isolation model.
- No secure-data co-residence claims for highly sensitive deployments; secure brains should be deployable separately.
- No hard-coded mortgage defaults in the generic brain. Mortgage behavior belongs in a mortgage brain profile, pack, and clarification rules.

## 4. Operating Modes

Each brain should have an explicit operating mode:

- `PUBLIC_SITE`: public website assistant for unauthenticated visitors.
- `PRIVATE_SITE`: authenticated or internal assistant that may use internal sources.
- `SECURE_DEPLOYMENT`: high-trust deployment that should run separately with isolated database, keys, admin credential, and model policy.

Each request should carry a `surface`:

- `PUBLIC`
- `INTERNAL`
- `SECURE`

The retrieval layer must enforce source visibility before model prompt assembly. A public request can only retrieve public-approved sources. The answer layer must not receive internal or secure context for public website requests.

## 5. Public Assistant Behavior

The public website assistant is a guided Q&A assistant, not a passive search box.

It should classify each turn into one response path:

- `ANSWER`: enough source-backed evidence exists to answer.
- `CLARIFY`: the answer depends on missing facts.
- `NAVIGATE`: the user mainly wants a relevant page, tool, or next step.
- `ESCALATE`: the request is too personalized, unsupported, sensitive, ambiguous, or low-confidence.

Default behavior:

- Answer only from approved indexed sources.
- Lead with a short conversational answer.
- Include citations when source context is used.
- Recommend relevant website pages when available.
- Ask one focused follow-up question when more context is needed.
- Escalate personalized or high-risk questions to a human when source evidence and collected facts are insufficient.
- Avoid presenting underwriting, eligibility, legal, financial, medical, or other regulated decisions as final determinations.

For mortgage use, the mortgage brain can configure missing facts such as occupancy, property type, loan purpose, program, credit range, down payment, state, and timeline. The platform should not bake those facts into generic code.

## 6. Brain Profiles

A brain profile controls how a brain behaves and speaks.

Profile fields should include:

- `purpose`: what the brain is for.
- `audience`: public visitor, customer, staff, expert, borrower, vendor, etc.
- `personality`: concise description of voice and role.
- `tone`: conversational, professional, technical, coaching, support, etc.
- `expertiseLevel`: beginner, intermediate, expert, or custom.
- `answerLength`: concise, balanced, detailed.
- `confidenceTarget`: default `0.90` for customer-facing answer-like guidance.
- `clarificationPolicy`: when to ask questions before answering.
- `escalationPolicy`: when to hand off to a human.
- `citationPolicy`: how aggressively citations are required.
- `ctaPolicy`: which calls to action are allowed.
- `disclaimer`: brain-specific disclaimer text.
- `publicMode`: whether public ask tokens and website embedding are enabled.

Profiles should be editable from the dashboard and included in trace logs so behavior changes are auditable.

## 7. Clarification And Confidence

Clarification should be first-class in the RAG pipeline.

The system should calculate confidence from:

- retrieval strength
- source trust level
- source visibility
- missing required facts
- source freshness
- source conflict
- answer validation
- whether citations support the answer

Each brain can define topic-level clarification requirements. A clarification rule contains:

- topic or intent
- required facts
- question wording
- priority
- whether the fact is required for public answers
- whether the fact is optional for general education

The assistant asks one focused question at a time and remembers facts already collected in the current session.

Example response path:

1. User asks: "Can I use FHA for a duplex?"
2. Brain classifies topic as eligibility/product guideline.
3. Brain sees `occupancy` is missing.
4. Brain responds: "Is the duplex going to be your primary residence?"
5. Once answered, retrieval and answer generation use the collected fact.

## 8. Learning Sources

Learning sources are the controlled input layer for what a brain knows.

Supported source types:

- uploaded document
- local folder
- S3 object/prefix
- single URL
- domain
- sitemap
- manual note
- approved external source link
- page guide/navigation rule

Each source should track:

- brain id
- source type
- title/name
- canonical URL or storage path
- trust level
- visibility
- status
- last learned time
- current version id
- crawl/ingest error if any
- created/updated metadata

Trust levels:

- `AUTHORITATIVE`
- `APPROVED`
- `REFERENCE`
- `EXPERIMENTAL`
- `BLOCKED`

Visibility levels:

- `PUBLIC`
- `INTERNAL`
- `SECURE`

Public website requests can use only `PUBLIC` sources that are not blocked.

## 9. URL Learning

URLs should be learned and snapshotted, not browsed live during normal answer generation.

URL learning flow:

```text
Admin adds URL/domain/sitemap
→ crawler discovers candidate pages
→ admin previews pages
→ admin approves pages for learning
→ system fetches approved pages
→ system extracts readable text
→ system creates a source version
→ system chunks hierarchically
→ system embeds child chunks
→ system writes vectors to pgvector
→ source becomes searchable and citeable
```

URL learning controls:

- domain allowlist
- path allowlist/blocklist
- depth limit
- page count limit
- content type allowlist
- sitemap preference
- robots/crawl-policy awareness
- extraction preview
- duplicate detection by content hash
- manual approval before ingest
- manual recrawl in v1

Every URL ingest creates a source version. Citations and traces should be able to point to the exact version used for an answer.

## 10. Public Assistant API

The public website assistant should use a dedicated public-safe API instead of the admin test console.

Public authentication:

- per-brain public ask token
- allowed domain list
- rate limits
- no admin privileges
- no trace details returned to the browser

Request fields:

- `sessionId`
- `message`
- `pageRoute`
- `surface`
- optional visitor context
- optional facts already collected

Response fields:

- `responseType`: `ANSWER`, `CLARIFY`, `NAVIGATE`, or `ESCALATE`
- `message` or `answer`
- `clarifyingQuestion`
- `missingFacts`
- `citations`
- `recommendedPages`
- `confidence`
- `nextAction`
- `traceId`

Example clarification response:

```json
{
  "responseType": "CLARIFY",
  "message": "Is this for a primary residence, second home, or investment property?",
  "missingFacts": ["occupancy"],
  "recommendedPages": [],
  "confidence": 0.42
}
```

Example answer response:

```json
{
  "responseType": "ANSWER",
  "answer": "Based on the approved source, FHA may allow certain 2-unit primary residence scenarios, but the final answer depends on the full scenario and current guideline details.",
  "confidence": 0.92,
  "citations": [],
  "recommendedPages": [
    {
      "label": "FHA loan options",
      "url": "/loans/fha",
      "reason": "Matches the loan program mentioned in the question."
    }
  ],
  "nextAction": "Review the FHA page or talk with a loan expert for a scenario-specific review."
}
```

## 11. Website Widget

V1 should include a basic embeddable website assistant or documented JavaScript snippet.

Widget capabilities:

- chat input
- conversational answer display
- clarifying question display
- recommended page links
- citations/source links
- human handoff button or call to action
- current page route sent with each request
- public token configuration
- allowed-domain enforcement on the API side

The widget should be intentionally small. V1 should ship a minimal snippet with only essential styling options; deeper theme controls are deferred until the API contract is stable.

## 12. Dashboard Experience

The dashboard should become the control center for building and operating brains.

V1 priority screens:

- **Brains:** create/select brains, mode, public token status, allowed domains, source visibility defaults, confidence target.
- **Personality:** voice, role, audience, answer length, strictness, refusal style, clarification behavior, calls to action.
- **Learning Sources:** files, URLs, domains, sitemaps, folders, notes, trust level, visibility, source status, version, chunk count, embedding status, errors.
- **URL Learning:** add URL/domain/sitemap, preview discovered pages, approve pages, set crawl limits, block paths, trigger ingest.
- **Page Guide / Navigation:** map intents to recommended site pages.
- **Test Console:** simulate public website questions with page route, surface, missing facts, and visitor context.
- **Trace Explorer:** show response type, query rewrite, retrieval plan, clarification decision, chunks considered, chunks used, citations, source visibility, recommended pages, model response, validation result, and confidence reason.
- **Evaluation:** save golden questions and run regression checks after changing sources, prompts, models, or personality.
- **Feedback:** review thumbs up/down, missing-source reports, bad citations, and human-handoff requests.

The first dashboard implementation can extend the existing screens rather than replacing the app shell.

## 13. Data Model Additions

The existing schema already has brains, documents, chunks, source links, page guides, traces, and admin settings. This design adds or expands around those primitives.

Recommended additions:

- `brain_profiles`
- `learning_sources`
- `source_versions`
- `clarification_rules`
- `conversation_turns`
- `feedback_events`

Recommended trace expansion:

- response type
- clarification decision
- missing facts
- collected facts
- retrieval plan
- source visibility filters
- sources considered
- sources used
- recommended pages
- confidence reason
- validation outcome

Hierarchical chunking remains:

- parent document sections provide answer context
- child chunks are embedded and retrieved
- public/private visibility is enforced before retrieval and context assembly

## 14. Pipeline

Customer-facing ask pipeline:

```text
public request
→ authenticate public ask token
→ validate allowed domain
→ resolve brain profile
→ classify intent
→ load session facts
→ decide answer/clarify/navigate/escalate
→ if clarify: return one focused question
→ if navigate: return recommended pages and short message
→ rewrite query if needed
→ plan retrieval
→ enforce source visibility
→ retrieve child chunks with pgvector/HNSW + keyword search
→ assemble parent context
→ generate source-grounded response
→ validate citations and guardrails
→ calculate confidence
→ attach recommended pages/next action
→ log trace
→ return public-safe response
```

## 15. Security And Isolation

Public website assistants must not expose admin capability.

Security rules:

- public ask token is separate from `ADMIN_API_KEY`
- public API cannot ingest, edit settings, view traces, or list documents
- allowed domains are enforced server-side
- public requests retrieve only `PUBLIC` source versions/chunks
- admin dashboard remains behind admin auth
- secure/private brains can run in a separate deployment when data sensitivity requires a real isolation boundary
- secure deployments can disable cloud fallback and use private/local models

For high-trust sensitive deployments, separate process + separate database + separate credentials is the honest isolation model.

## 16. Evaluation And Feedback

Evaluation should become part of the admin workflow.

Each brain should support saved test cases:

- question
- expected response type
- required/forbidden citations
- expected recommended page
- expected missing facts
- expected escalation behavior
- notes

Feedback events should capture:

- helpful/unhelpful
- missing source
- bad citation
- answer too long
- answer too vague
- wanted human
- use this as test case

The system should let admins convert failed conversations into test cases, source requests, clarification rules, or prompt/profile edits.

## 17. Suggested Build Order

1. **Brain Profile**
   Add profile storage, dashboard editing, and profile-aware prompt assembly.

2. **Public Ask Contract**
   Add a public-safe endpoint and response type: `ANSWER`, `CLARIFY`, `NAVIGATE`, `ESCALATE`.

3. **Clarification Engine**
   Add missing-fact rules, collected session facts, and one-question-at-a-time clarification behavior.

4. **Source Visibility Enforcement**
   Ensure public retrieval sees only public-approved source versions/chunks.

5. **Learning Sources**
   Add first-class source records and source versions around existing documents/chunks.

6. **URL Learning MVP**
   Add single URL/sitemap/domain discovery, preview, approval, extraction, snapshot, chunk, embed, and ingest.

7. **Dashboard Upgrade**
   Add Personality, Learning Sources, URL Learning, upgraded Test Console, and Trace Explorer views.

8. **Website Embed Starter**
   Add a basic widget or documented JavaScript snippet for the hero-page assistant.

9. **Evaluation And Feedback**
   Add saved test cases, feedback events, and regression run support.

## 18. V1 Implementation Decisions

- The first widget ships as a documented API plus a minimal embeddable JavaScript snippet in this repo.
- URL learning initially supports single URLs and sitemaps. Broad domain crawling is deferred until single-page and sitemap learning are reliable.
- Source versions store extracted text in PostgreSQL for v1. Raw files/pages can remain in their source storage path; object-storage snapshots can be added later if size or audit needs require it.
- Public ask tokens are opaque database tokens stored hashed at rest. Signed tokens are deferred until there is a concrete cross-service use case.
- Dashboard work should add focused screens for Personality and URL Learning, while extending existing Brains, Corpus, Test Console, Page Guides, and Trace views where that keeps the UI simpler.

## 19. Acceptance Criteria

- A public website request can return `ANSWER`, `CLARIFY`, `NAVIGATE`, or `ESCALATE`.
- A public request cannot retrieve internal or secure sources.
- A brain profile can change personality/style/confidence behavior without code changes.
- The assistant can ask a focused clarifying question before answering when required facts are missing.
- The assistant can recommend relevant website pages as structured links.
- Admins can add at least one URL source, preview/extract it, ingest it, and query it with citations.
- Traces show the decision path, including clarification decisions and source visibility filters.
- Existing document ingestion, hierarchical chunking, pgvector retrieval, and dashboard workflows continue to work.
