import { useMemo, useState } from "react";
import { api, publicAsk } from "../api";
import { AskResponse, PublicAskResponse, RetrievalResult } from "../types";
import { ErrorNote, Pill } from "../components";

export default function TestConsole({ slug }: { slug: string }) {
  const [mode, setMode] = useState<"ask" | "retrieval" | "public">("ask");
  const [question, setQuestion] = useState("What is PMI?");
  const [pageRoute, setPageRoute] = useState("");
  const [surface, setSurface] = useState("PUBLIC");
  const [publicToken, setPublicToken] = useState("");
  const [origin, setOrigin] = useState(() => window.location.origin);
  const [factsText, setFactsText] = useState("{}");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AskResponse | null>(null);
  const [publicAnswer, setPublicAnswer] = useState<PublicAskResponse | null>(null);
  const [retrieval, setRetrieval] = useState<RetrievalResult | null>(null);
  const [elapsed, setElapsed] = useState<number | null>(null);
  const [sessionId] = useState(() => `dashboard-${crypto.randomUUID()}`);
  const normalizedOrigin = useMemo(() => window.location.origin, []);

  function parseFacts(): Record<string, unknown> {
    const raw = factsText.trim();
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      throw new Error("Facts must be a JSON object");
    }
    return parsed as Record<string, unknown>;
  }

  async function run() {
    setBusy(true);
    setError(null);
    setAnswer(null);
    setPublicAnswer(null);
    setRetrieval(null);
    setElapsed(null);
    const start = performance.now();
    try {
      if (!slug.trim()) {
        throw new Error("Active brain slug is unavailable");
      }
      if (mode === "ask") {
        setAnswer(await api.post<AskResponse>(`/api/ai/${slug}/ask`, {
          sessionId,
          question,
          pageRoute: pageRoute.trim() || null,
          surface,
        }));
      } else if (mode === "public") {
        const typedOrigin = origin.trim();
        if (!publicToken.trim()) {
          throw new Error("Public token is required");
        }
        if (!typedOrigin) {
          throw new Error("Origin is required");
        }
        if (typedOrigin !== normalizedOrigin) {
          throw new Error(`Browser requests use ${normalizedOrigin}. Update the allowed domain or open the dashboard from that origin.`);
        }
        setPublicAnswer(await publicAsk(slug, publicToken.trim(), {
          sessionId,
          message: question,
          pageRoute: pageRoute.trim() || null,
          surface: "PUBLIC",
          facts: parseFacts(),
        }));
      } else {
        setRetrieval(await api.get<RetrievalResult>(
          `/api/ai/documents/test-retrieval?question=${encodeURIComponent(question)}`));
      }
      setElapsed((performance.now() - start) / 1000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const publicTone = (responseType: PublicAskResponse["responseType"]): "green" | "amber" | "gray" => {
    if (responseType === "ANSWER") return "green";
    if (responseType === "CLARIFY") return "amber";
    return "gray";
  };

  return (
    <>
      <header className="screen-head">
        <h1>Test console</h1>
        <div className="mode-toggle">
          <button className={mode === "ask" ? "on" : ""} onClick={() => setMode("ask")}>Full ask</button>
          <button className={mode === "public" ? "on" : ""} onClick={() => setMode("public")}>Public ask</button>
          <button className={mode === "retrieval" ? "on" : ""} onClick={() => setMode("retrieval")}>Retrieval only</button>
        </div>
      </header>
      <div className="ask-bar">
        <input value={question} onChange={(e) => setQuestion(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && !busy && run()} />
        <button className="btn-primary" onClick={run} disabled={busy || !question.trim() || !slug.trim()}>
          {busy ? "Working…" : mode === "retrieval" ? "Retrieve" : "Ask"}
        </button>
      </div>
      <div className="ask-bar" style={{ marginTop: 8 }}>
        <input placeholder="page route (optional)" value={pageRoute}
               onChange={(e) => setPageRoute(e.target.value)} />
        {mode === "ask" ? (
          <select value={surface} onChange={(e) => setSurface(e.target.value)}>
            <option value="PUBLIC">public</option>
            <option value="INTERNAL">internal</option>
            <option value="BOTH">both</option>
          </select>
        ) : mode === "public" ? (
          <input placeholder="public token" value={publicToken} onChange={(e) => setPublicToken(e.target.value)} />
        ) : (
          <input value={slug} readOnly />
        )}
      </div>
      {mode === "public" && (
        <>
          <div className="ask-bar" style={{ marginTop: 8 }}>
            <input value={origin} onChange={(e) => setOrigin(e.target.value)} />
            <span className="muted">browser sends {normalizedOrigin}</span>
          </div>
          <textarea
            className="console-textarea"
            value={factsText}
            onChange={(e) => setFactsText(e.target.value)}
            placeholder='{"loanType":"fha"}'
          />
        </>
      )}
      <ErrorNote message={error} />
      {answer && (
        <div className="card">
          <div className="chips">
            <Pill tone={answer.humanEscalationRequired ? "amber" : "green"}>
              {answer.humanEscalationRequired ? "escalated" : "grounded"}</Pill>
            <Pill tone="gray">confidence {answer.confidence.toFixed(2)}</Pill>
            <Pill tone="gray">{answer.citations.length} citations</Pill>
            {answer.traceId && <Pill tone="gray">trace {answer.traceId.slice(0, 8)}</Pill>}
            {elapsed !== null && <Pill tone="gray">{elapsed.toFixed(1)} s</Pill>}
          </div>
          <p className="answer">{answer.answer}</p>
          {answer.citations.length > 0 && (
            <ul className="citations">
              {answer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          {answer.recommendedPage && (
            <p className="muted">Recommended page: {answer.recommendedPage.label} ({answer.recommendedPage.route})</p>
          )}
          {answer.links && answer.links.length > 0 && (
            <ul className="citations">
              {answer.links.map((l, i) => (
                <li key={i}><a href={l.url} target="_blank" rel="noreferrer">{l.name}</a> · {l.authority.toLowerCase()}</li>
              ))}
            </ul>
          )}
          {answer.nextAction && <p className="muted">{answer.nextAction}</p>}
          <p className="muted">{answer.disclaimer}</p>
        </div>
      )}
      {publicAnswer && (
        <div className="card">
          <div className="chips">
            <Pill tone={publicTone(publicAnswer.responseType)}>
              {publicAnswer.responseType.toLowerCase()}
            </Pill>
            <Pill tone="gray">confidence {publicAnswer.confidence.toFixed(2)}</Pill>
            {publicAnswer.citations.length > 0 && <Pill tone="gray">{publicAnswer.citations.length} citations</Pill>}
            {elapsed !== null && <Pill tone="gray">{elapsed.toFixed(1)} s</Pill>}
          </div>
          {publicAnswer.message && <p className="muted">{publicAnswer.message}</p>}
          {publicAnswer.clarifyingQuestion && <p className="answer">{publicAnswer.clarifyingQuestion}</p>}
          {publicAnswer.answer && <p className="answer">{publicAnswer.answer}</p>}
          {publicAnswer.missingFacts.length > 0 && (
            <p className="muted">Missing: {publicAnswer.missingFacts.join(", ")}</p>
          )}
          {publicAnswer.citations.length > 0 && (
            <ul className="citations">
              {publicAnswer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          {publicAnswer.recommendedPages.length > 0 && (
            <ul className="citations">
              {publicAnswer.recommendedPages.map((p) => (
                <li key={p.url}>{p.label} ({p.url}) - {p.reason}</li>
              ))}
            </ul>
          )}
          {publicAnswer.nextAction && <p className="muted">{publicAnswer.nextAction}</p>}
        </div>
      )}
      {retrieval && (
        <div className="card">
          <div className="chips">
            <Pill tone={retrieval.sufficientEvidence ? "green" : "amber"}>
              {retrieval.sufficientEvidence ? "sufficient evidence" : "insufficient evidence"}</Pill>
            <Pill tone="gray">confidence {retrieval.confidence.toFixed(2)}</Pill>
            <Pill tone="gray">{retrieval.chunks.length} chunks</Pill>
            {elapsed !== null && <Pill tone="gray">{elapsed.toFixed(1)} s</Pill>}
          </div>
          {retrieval.chunks.map((chunk, i) => (
            <div key={i} className="chunk">
              <div className="chunk-head">
                <strong>{chunk.sourceName}</strong>
                <span className="muted">{[chunk.section, chunk.pageNumber ? `p. ${chunk.pageNumber}` : null]
                  .filter(Boolean).join(" — ")}</span>
                <Pill tone="gray">{chunk.combinedScore.toFixed(2)}</Pill>
              </div>
              <p>{chunk.content.length > 280 ? `${chunk.content.slice(0, 280)}…` : chunk.content}</p>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
