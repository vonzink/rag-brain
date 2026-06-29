# Public Assistant

The public assistant endpoint is for website visitors. It uses a per-brain public token and allowed-domain checks. It does not accept `ADMIN_API_KEY`, and it cannot mutate sources, prompts, profiles, admin settings, traces, or documents.

Endpoint:

```text
POST /api/ai/public/{slug}/ask
```

Required headers:

```text
Origin: https://example.com
X-Public-Brain-Token: rb_pub_...
Content-Type: application/json
```

Request:

```json
{
  "conversationId": null,
  "sessionId": "hero-test",
  "message": "What can you help me with?",
  "pageRoute": "/",
  "surface": "PUBLIC",
  "facts": {}
}
```

Field notes:

- `conversationId` is optional. Send the `conversationId` returned by the previous response to continue that conversation.
- `sessionId` is required and should stay stable for the current website visitor session.
- `message` is the visitor's question.
- `pageRoute` is optional page context from the website.
- `surface` should be `PUBLIC`. Public requests are enforced to `PUBLIC` server-side even if another value is sent.
- `facts` is an optional object for known context collected by the website.

Response:

```json
{
  "responseType": "ANSWER",
  "message": "I can help explain your available options.",
  "answer": "I can help explain your available options.",
  "clarifyingQuestion": null,
  "missingFacts": [],
  "citations": [],
  "recommendedPages": [],
  "confidence": 0.82,
  "nextAction": null,
  "conversationId": "8f4bc9a7-8b30-4ef9-9625-08b50bca7d9f"
}
```

Response types:

- `ANSWER`: source-grounded answer.
- `CLARIFY`: one missing fact is needed before a high-confidence answer.
- `NAVIGATE`: the user mainly needs a relevant page or next step.
- `ESCALATE`: the system should hand off to a human.

Visibility behavior:

- Public requests retrieve only `PUBLIC` sources.
- Internal and secure sources are filtered before prompt assembly.
- Public callers cannot ingest documents, edit settings, view traces, or list documents.
- Public website assistant tokens are generated per brain and stored hashed in PostgreSQL.
