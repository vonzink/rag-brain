import { useCallback, useEffect, useState } from "react";
import { brainsApi } from "../api";
import { BrainAdminDto, SyncReport } from "../types";
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

  const reload = useCallback(() => {
    brainsApi.list().then(setBrains).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

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
