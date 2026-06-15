import { useCallback, useEffect, useState } from "react";
import { api, brainsApi } from "../api";
import { BrainAdminDto, BrainCreateRequest, SettingsResponse, SyncReport } from "../types";
import { ErrorNote, Pill } from "../components";

function sourceSummary(b: BrainAdminDto): string {
  if (b.sourceType === "local") return `local: ${b.localPath ?? "—"}`;
  if (b.sourceType === "s3") return `s3: ${b.s3Bucket ?? "—"}${b.s3Prefix ? "/" + b.s3Prefix : ""}`;
  return "—";
}

export default function Brains() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [providers, setProviders] = useState<string[]>([]);
  const blankForm: BrainCreateRequest = {
    slug: "", displayName: "", packRef: "packs/msfg-mortgage", sourceType: "local",
    s3Bucket: null, s3Prefix: null, s3Region: null, localPath: "",
    answerProvider: "anthropic", answerModel: "", utilityProvider: "openai", utilityModel: "",
  };
  const [form, setForm] = useState<BrainCreateRequest>(blankForm);
  const [creating, setCreating] = useState(false);

  const reload = useCallback(() => {
    brainsApi.list().then(setBrains).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

  useEffect(() => {
    api.get<SettingsResponse>("/api/ai/admin/settings")
      .then((s) => setProviders(s.providers.filter((p) => p.configured).map((p) => p.name)))
      .catch(() => setProviders([]));
  }, []);

  function set<K extends keyof BrainCreateRequest>(k: K, v: BrainCreateRequest[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  async function create() {
    setCreating(true); setError(null);
    try {
      await brainsApi.create(form);
      setForm(blankForm);
      reload();
    } catch (e) { setError((e as Error).message); }
    finally { setCreating(false); }
  }

  const providerOptions = (current: string) =>
    providers.includes(current) || !current ? providers : [current, ...providers];

  async function setActive(b: BrainAdminDto) {
    setBusy(b.id); setError(null);
    try { await brainsApi.activate(b.id); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function sync(b: BrainAdminDto) {
    setBusy(b.id); setError(null); setReport(null);
    try { setReport(await brainsApi.sync(b.id, false)); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const summaryTone = (k: string): "green" | "amber" | "gray" =>
    k === "upload" || k === "update" ? "green" : k === "deactivate" ? "amber" : "gray";

  return (
    <>
      <header className="screen-head">
        <h1>Brains</h1>
        <span className="muted">create a brain, point it at a folder or bucket, sync, then set active</span>
      </header>
      <ErrorNote message={error} />
      <div className="card">
        <h2>Create brain</h2>
        <div className="setting-row">
          <label>Display name</label>
          <input value={form.displayName} onChange={(e) => set("displayName", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Slug (lowercase, a–z 0–9 -)</label>
          <input value={form.slug} onChange={(e) => set("slug", e.target.value)}
                 placeholder="lending" />
        </div>
        <div className="setting-row">
          <label>Pack ref</label>
          <input value={form.packRef} onChange={(e) => set("packRef", e.target.value)} />
        </div>
        <p className="muted">The pack's internal <code>slug</code> must equal this brain's slug. Copy <code>packs/msfg-mortgage</code> to a new folder, set its <code>slug</code>, and point here.</p>
        <div className="setting-row">
          <label>Source</label>
          <div className="mode-toggle">
            <button className={form.sourceType === "local" ? "on" : ""}
                    onClick={() => set("sourceType", "local")}>Local folder</button>
            <button className={form.sourceType === "s3" ? "on" : ""}
                    onClick={() => set("sourceType", "s3")}>S3</button>
          </div>
        </div>
        {form.sourceType === "local" ? (
          <div className="setting-row">
            <label>Folder path</label>
            <input value={form.localPath ?? ""} onChange={(e) => set("localPath", e.target.value)}
                   placeholder="/Users/you/corpora/lending" />
          </div>
        ) : (
          <>
            <div className="setting-row">
              <label>S3 bucket</label>
              <input value={form.s3Bucket ?? ""} onChange={(e) => set("s3Bucket", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 prefix</label>
              <input value={form.s3Prefix ?? ""} onChange={(e) => set("s3Prefix", e.target.value)} />
            </div>
            <div className="setting-row">
              <label>S3 region</label>
              <input value={form.s3Region ?? ""} onChange={(e) => set("s3Region", e.target.value)} />
            </div>
          </>
        )}
        <div className="setting-row">
          <label>Answer provider</label>
          <select value={form.answerProvider} onChange={(e) => set("answerProvider", e.target.value)}>
            {providerOptions(form.answerProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Answer model</label>
          <input value={form.answerModel} onChange={(e) => set("answerModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        <div className="setting-row">
          <label>Utility provider</label>
          <select value={form.utilityProvider} onChange={(e) => set("utilityProvider", e.target.value)}>
            {providerOptions(form.utilityProvider).map((p) => <option key={p}>{p}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Utility model</label>
          <input value={form.utilityModel} onChange={(e) => set("utilityModel", e.target.value)}
                 placeholder="blank = provider default" />
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={create}
                  disabled={creating || !form.slug.trim() || !form.displayName.trim()
                            || (form.sourceType === "local" ? !form.localPath?.trim() : !form.s3Bucket?.trim())}>
            {creating ? "Creating…" : "Create brain"}
          </button>
        </div>
      </div>
      {report && (
        <div className="card sync-report">
          <div className="sync-summary">
            <strong>Sync finished</strong>
            {Object.entries(report.summary).map(([k, v]) => (
              <Pill key={k} tone={summaryTone(k)}>{v} {k}</Pill>
            ))}
          </div>
          {report.results.filter((r) => r.action !== "SKIP" || r.error).map((r) => (
            <div key={`${r.action}-${r.fileName}`} className="diff-line">
              <code>{r.action.toLowerCase()}</code>
              <span>{r.fileName}{r.reason ? ` — ${r.reason}` : ""}{r.error ? ` — FAILED: ${r.error}` : ""}</span>
            </div>
          ))}
        </div>
      )}
      <table className="tbl">
        <thead>
          <tr><th>Brain</th><th>Slug</th><th>Source</th><th>Answer model</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {brains.map((b) => (
            <tr key={b.id}>
              <td>{b.displayName}</td>
              <td><code>{b.slug}</code></td>
              <td>{sourceSummary(b)}</td>
              <td>{b.answerProvider ?? "—"}{b.answerModel ? ` / ${b.answerModel}` : ""}</td>
              <td>
                {b.isDefault && <Pill tone="green">active</Pill>}
                {!b.isActive && <Pill tone="gray">disabled</Pill>}
                {b.isActive && !b.isDefault && <Pill tone="gray">idle</Pill>}
              </td>
              <td className="row-actions">
                <button onClick={() => sync(b)} disabled={busy === b.id || !b.isActive}>
                  {busy === b.id ? "Working…" : "Sync now"}
                </button>
                <button onClick={() => setActive(b)} disabled={busy === b.id || b.isDefault || !b.isActive}>
                  Set active
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
