import { useCallback, useEffect, useMemo, useState } from "react";
import { brainsApi, connectorsApi } from "../api";
import { BrainAdminDto, ConnectorClient, ConnectorClientRequest, ConnectorEvent } from "../types";
import { ErrorNote, Pill } from "../components";
import { federationCurlSnippet, mcpConfigSnippet, peerRegistrationSnippet } from "../connect/connectorSnippets";

const TYPES: ConnectorClient["type"][] = ["MCP_AGENT", "PEER_BRAIN", "SERVER_API", "INTERNAL_APP"];
const SCOPES = ["brains:list", "brain:read", "ask:public", "retrieve:public", "citations:read", "readiness:read"];

type SnippetTab = "mcp" | "curl" | "peer";

function emptyForm(defaultBrainId: string | null): ConnectorClientRequest {
  return {
    name: "",
    type: "MCP_AGENT",
    brainId: defaultBrainId,
    scopes: ["brains:list", "brain:read", "ask:public", "retrieve:public", "readiness:read"],
    allowedOrigins: [],
    allowedPeerHosts: [],
    enabled: true,
  };
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button onClick={async () => {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }}>
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function Connectors() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [clients, setClients] = useState<ConnectorClient[]>([]);
  const [events, setEvents] = useState<ConnectorEvent[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<ConnectorClientRequest>(emptyForm(null));
  const [peerHosts, setPeerHosts] = useState("");
  const [origins, setOrigins] = useState("");
  const [apiBase, setApiBase] = useState(window.location.origin);
  const [token, setToken] = useState("");
  const [tab, setTab] = useState<SnippetTab>("mcp");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const selected = useMemo(() => clients.find((c) => c.id === selectedId) ?? clients[0] ?? null,
    [clients, selectedId]);
  const selectedBrain = useMemo(() => {
    if (selected?.brainId) return brains.find((b) => b.id === selected.brainId) ?? null;
    return brains.find((b) => b.isDefault) ?? brains[0] ?? null;
  }, [brains, selected]);

  const reload = useCallback(() => {
    Promise.all([brainsApi.list(), connectorsApi.list()])
      .then(([brainList, connectorList]) => {
        setBrains(brainList);
        setClients(connectorList);
        if (!form.brainId && brainList.length) {
          setForm((f) => ({ ...f, brainId: brainList.find((b) => b.isDefault)?.id ?? brainList[0].id }));
        }
      })
      .catch((e) => setError((e as Error).message));
  }, [form.brainId]);

  useEffect(reload, [reload]);

  useEffect(() => {
    if (!selected) {
      setEvents([]);
      return;
    }
    connectorsApi.events(selected.id).then(setEvents).catch(() => setEvents([]));
  }, [selected]);

  function set<K extends keyof ConnectorClientRequest>(key: K, value: ConnectorClientRequest[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  function toggleScope(scope: string) {
    setForm((f) => ({
      ...f,
      scopes: f.scopes.includes(scope) ? f.scopes.filter((s) => s !== scope) : [...f.scopes, scope],
    }));
  }

  function splitLines(text: string): string[] {
    return text.split(/[\n,]+/).map((s) => s.trim()).filter(Boolean);
  }

  async function create() {
    setBusy("create"); setError(null); setToken("");
    try {
      await connectorsApi.create({
        ...form,
        name: form.name.trim(),
        allowedOrigins: splitLines(origins),
        allowedPeerHosts: splitLines(peerHosts),
      });
      setForm(emptyForm(form.brainId));
      setOrigins(""); setPeerHosts("");
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function rotate(c: ConnectorClient) {
    setBusy(c.id); setError(null);
    try {
      const res = await connectorsApi.rotateToken(c.id);
      setToken(res.token);
      setSelectedId(c.id);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setEnabled(c: ConnectorClient, enabled: boolean) {
    setBusy(c.id); setError(null);
    try {
      await (enabled ? connectorsApi.enable(c.id) : connectorsApi.disable(c.id));
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const snippetParams = { apiBase, slug: selectedBrain?.slug ?? "generic", token };
  const snippet = tab === "mcp"
    ? mcpConfigSnippet(snippetParams)
    : tab === "curl"
      ? federationCurlSnippet(snippetParams)
      : peerRegistrationSnippet(snippetParams);

  return (
    <>
      <header className="screen-head">
        <h1>Connectors</h1>
        <span className="muted">scoped access for agents, peer brains, and server-side apps</span>
      </header>
      <ErrorNote message={error} />

      <div className="card">
        <h2>Create connector</h2>
        <div className="setting-row">
          <label>Name</label>
          <input value={form.name} onChange={(e) => set("name", e.target.value)}
                 placeholder="Cursor agent, partner brain, backend service" />
        </div>
        <div className="setting-row">
          <label>Type</label>
          <select value={form.type} onChange={(e) => set("type", e.target.value as ConnectorClient["type"])}>
            {TYPES.map((type) => <option key={type}>{type}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Brain</label>
          <select value={form.brainId ?? ""} onChange={(e) => set("brainId", e.target.value || null)}>
            <option value="">All active brains</option>
            {brains.map((b) => <option key={b.id} value={b.id}>{b.displayName} ({b.slug})</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Scopes</label>
          <div className="scope-grid">
            {SCOPES.map((scope) => (
              <label key={scope}>
                <input type="checkbox" checked={form.scopes.includes(scope)}
                       onChange={() => toggleScope(scope)} />
                <code>{scope}</code>
              </label>
            ))}
          </div>
        </div>
        <div className="setting-row">
          <label>Peer hosts</label>
          <textarea value={peerHosts} onChange={(e) => setPeerHosts(e.target.value)}
                    placeholder="brain.partner.com, internal.example.com" />
        </div>
        <div className="setting-row">
          <label>Browser origins</label>
          <textarea value={origins} onChange={(e) => setOrigins(e.target.value)}
                    placeholder="https://app.example.com" />
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={create}
                  disabled={busy === "create" || !form.name.trim() || form.scopes.length === 0}>
            {busy === "create" ? "Creating..." : "Create connector"}
          </button>
        </div>
      </div>

      <table className="tbl">
        <thead>
          <tr><th>Name</th><th>Type</th><th>Brain</th><th>Scopes</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {clients.map((c) => {
            const brain = brains.find((b) => b.id === c.brainId);
            return (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td><Pill tone="blue">{c.type.toLowerCase()}</Pill></td>
                <td>{brain ? brain.slug : "all"}</td>
                <td>{c.scopes.length}</td>
                <td>
                  <Pill tone={c.enabled ? "green" : "gray"}>{c.enabled ? "enabled" : "disabled"}</Pill>
                  {" "}
                  <Pill tone={c.hasToken ? "purple" : "amber"}>{c.hasToken ? "token issued" : "no token"}</Pill>
                </td>
                <td className="row-actions">
                  <button onClick={() => setSelectedId(c.id)}>Select</button>
                  <button onClick={() => rotate(c)} disabled={busy === c.id}>
                    {busy === c.id ? "Working..." : "Rotate token"}
                  </button>
                  <button onClick={() => setEnabled(c, !c.enabled)}>
                    {c.enabled ? "Disable" : "Enable"}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {selected && (
        <div className="card token-reveal">
          <h2>Snippets</h2>
          <p className="muted">
            Connector tokens are shown once after rotation. Rotate the selected connector if you need a fresh token.
          </p>
          {token && <p className="connector-token">New connector token: <code>{token}</code></p>}
          <div className="setting-row">
            <label>API base</label>
            <input value={apiBase} onChange={(e) => setApiBase(e.target.value)} />
          </div>
          <div className="mode-toggle">
            <button className={tab === "mcp" ? "on" : ""} onClick={() => setTab("mcp")}>MCP config</button>
            <button className={tab === "curl" ? "on" : ""} onClick={() => setTab("curl")}>Federation cURL</button>
            <button className={tab === "peer" ? "on" : ""} onClick={() => setTab("peer")}>Peer registration</button>
          </div>
          <div className="snippet">
            <div className="snippet-actions"><CopyButton text={snippet} /></div>
            <pre>{snippet}</pre>
          </div>
        </div>
      )}

      {selected && (
        <div className="card">
          <h2>Recent connector events</h2>
          {events.length === 0 && <p className="muted">No events recorded yet.</p>}
          {events.map((event) => (
            <div key={event.id} className="diff-line">
              <Pill tone={event.status === "200" ? "green" : event.status === "403" ? "amber" : "gray"}>
                {event.status}
              </Pill>
              <span>{event.eventType}{event.scope ? ` - ${event.scope}` : ""}</span>
              <span className="muted">{event.createdAt ?? ""}</span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
