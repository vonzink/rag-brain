# rag-brain Website Integration Contract

For the team/session building the public website chat widget.
The brain is a REST API; the website only needs two endpoints.

Base URL (local dev): `http://localhost:8091`
Base URL (production): TBD — will be `https://api.<domain>` behind Nginx on EC2.

---

## 1. Ask a question

```
POST /api/ai/public/{slug}/ask
Origin: https://www.example.com
X-Public-Brain-Token: <public brain token>
Content-Type: application/json
```

Request body:

```json
{
  "sessionId": "any-stable-id-for-this-visitor",
  "conversationId": "uuid-from-previous-response-or-omit",
  "message": "Can I use gift funds for my down payment?",
  "pageRoute": "/purchase",
  "surface": "PUBLIC",
  "facts": {
    "loanType": "conventional",
    "state": "CO"
  }
}
```

Field rules:
- `sessionId` (required, ≤255 chars): generate once per visitor (e.g. `crypto.randomUUID()`
  stored in `sessionStorage`). The same sessionId must be sent for follow-up questions.
- `conversationId` (optional): omit on first question; echo back the value from the
  previous response to continue the same conversation thread.
- `message` (required, ≤2000 chars).
- `pageRoute` (optional): current website route for page-aware guidance.
- `surface` (required): send `"PUBLIC"`.
- `facts` (optional): JSON object with known context hints such as `loanType`, `state`,
  occupancy, or purchase/refinance intent.
- `Origin` header must be the website origin that the backend has allowlisted for CORS.
- `X-Public-Brain-Token` is required on every public browser request.

Response (200):

```json
{
  "conversationId": "e5e48b02-...",
  "answer": "Gift funds may generally be used...",
  "citations": [
    {
      "source_name": "Fannie Mae Selling Guide",
      "document_name": "fannie mae sellers guide.pdf",
      "section": "B3-4.3-04 Personal Gifts",
      "page_number": "412",
      "effective_date": "2026-01-01"
    }
  ],
  "confidence": 0.8,
  "humanEscalationRequired": false,
  "disclaimer": "This answer is for general mortgage education only and is not a loan approval, underwriting decision, legal advice, or tax advice."
}
```

UI requirements (compliance — not optional):
- ALWAYS render the `disclaimer` with every answer.
- When `humanEscalationRequired` is true, show a prominent "Talk to a licensed
  loan officer" call-to-action alongside the answer.
- Render `citations` under the answer ("Sources: ..."). Fields may be null — skip
  null fields when formatting.
- Citation fields can contain newlines; sanitize for display.

Errors:
- `400` — validation problem, body `{"error": "message is required"}`. Show the message.
- `401` — missing or invalid public token, body `{"error": "..."}`. Show a generic
  unavailable message and fall back to a contact CTA.
- `429` — rate limited (10 questions/minute per IP), body `{"error": "..."}`.
  Show "You're asking questions quickly — give it a few seconds."
- `500` — body `{"error": "..."}`. Show a generic retry message.

Latency: typically 3–10 seconds (retrieval + reranking + generation).
Show a typing/thinking indicator; do not set client timeouts below 60s.

---

## 1b. Drop-in widget (no build step)

The fastest way to attach a brain to a website. Paste this before `</body>`; it
renders a floating chat button and panel that calls the public endpoint and
enforces the compliance UI (always shows the disclaimer, renders citations, and
surfaces a human-handoff CTA when `humanEscalationRequired` is true):

```html
<script src="https://your-brain-host/widget/rag-brain-widget.js"
        data-rag-api="https://your-brain-host"
        data-rag-slug="your-brain-slug"
        data-rag-token="rb_pub_..."
        data-rag-title="Ask us anything"
        defer></script>
```

Attributes:
- `data-rag-api` (required): base URL of the brain host (no trailing slash).
- `data-rag-slug` (required): the brain's slug.
- `data-rag-token` (required): the public token (`rb_pub_...`).
- `data-rag-title` (optional): header text for the panel.
- `data-rag-accent` (optional): accent color, e.g. `#2563eb`.

The widget script is served by the brain host at `/widget/rag-brain-widget.js`.
The site's origin must be allowlisted on the brain (see the installer below).

---

## Connecting via the dashboard installer

The dashboard has a **Connect** screen that walks through the whole setup:

1. **Choose brain** — pick which brain to attach; a live checklist shows what's left.
2. **Knowledge** — confirm the corpus is synced (the assistant only answers from approved sources).
3. **Voice & compliance** — set the disclaimer, purpose, audience, tone, and confidence target.
4. **Publish** — list the website domain(s), enable public access, and generate the public token.
   Entered domains are authorized for cross-origin (CORS) requests **immediately** — no restart —
   because the public endpoints use per-brain dynamic CORS driven by the brain profile's allowed
   domains (the static `CORS_ALLOWED_ORIGINS` list still applies as well).
5. **Embed** — copy a ready-to-paste widget snippet or server snippet (cURL / JavaScript / Python).
6. **Verify** — send a live test question through the public endpoint to confirm it works.

The public token is shown once and stored only as a hash; regenerating it invalidates the old one.

---

## Website tokens vs connector tokens

Website embeds use public brain tokens (`rb_pub_...`) through `X-Public-Brain-Token`.
Do not use connector tokens in browser code.

Connector clients use bearer tokens (`rb_conn_...`) for server apps, agent tools,
and peer `rag-brain` instances. They call `/.well-known/rag-brain.json`,
`/api/connect/v1/**`, or `/mcp/tools`, and they are managed from the dashboard
**Connectors** screen. Connector tokens are also shown once and stored only as
hashes.

Internal dashboard assistants should use connector tokens only from the
dashboard backend. The browser sends the user's chat message to the dashboard
backend; the dashboard backend adds authenticated user context and calls
`/api/connect/v1/brains/{slug}/dashboard/**`. Use public tokens only for public
website widgets.

Internal dashboard MCP-style tool names:

- `rag_brain_dashboard_tools`
- `rag_brain_dashboard_tool_call`
- `rag_brain_dashboard_ask`

The first dashboard-tool implementation is a stub contract. Read tools and
confirmed write tools return `STUBBED` until a dashboard API adapter is connected.
Unconfirmed write tools return `CONFIRMATION_REQUIRED`.

---

## 2. Conversation history (optional)

```
GET /api/ai/conversations/{conversationId}
X-Session-Id: <the same sessionId used to create it>
```

Returns 404 if the conversationId doesn't exist OR belongs to a different session.

```json
{
  "id": "e5e48b02-...",
  "createdAt": "2026-06-05T13:55:00Z",
  "messages": [
    {"role": "user", "content": "...", "createdAt": "..."},
    {"role": "assistant", "content": "...", "createdAt": "..."}
  ]
}
```

---

## CORS

The public endpoints (`POST /api/ai/public/**` and `GET /api/ai/conversations/**`)
use **dynamic per-brain CORS**: any origin whose host is listed in a brain's
allowed domains (set in the Connect installer / brain profile) is authorized
automatically, with no restart. The static `CORS_ALLOWED_ORIGINS` list
(local dev defaults: `http://localhost:3000`, `http://localhost:5173`) is always
honored too. Admin endpoints remain env-allowlisted and are not exposed for
public browser use, by design.

---

## Things the website must NOT do

- Do not call admin endpoints (`/api/ai/documents/**`) from the browser, ever.
- Do not embed admin API keys in frontend code. Public website calls must send the
  issued `X-Public-Brain-Token`, which is scoped for public ask only.
- Do not rewrite, truncate, or paraphrase `answer`, `disclaimer`, or citations.
- Do not retry failed requests in a tight loop (the rate limiter will lock the visitor out).
- Do not implement your own mortgage answers as fallback when the API refuses —
  the refusal IS the correct, compliant behavior.
