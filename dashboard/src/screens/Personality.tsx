import { useEffect, useMemo, useState } from "react";
import { profileApi } from "../api";
import { BrainProfileDto, BrainProfileRequest, Stats } from "../types";
import { ErrorNote, Pill } from "../components";

function toRequest(profile: BrainProfileDto): BrainProfileRequest {
  const { brainId: _brainId, ...request } = profile;
  return request;
}

function defaultProfile(brainId: string): BrainProfileDto {
  return {
    brainId,
    mode: "PUBLIC_SITE",
    purpose: "",
    audience: "",
    personality: "",
    tone: "",
    expertiseLevel: "",
    answerLength: "",
    confidenceTarget: 0.7,
    clarificationPolicy: "",
    escalationPolicy: "",
    citationPolicy: "",
    ctaPolicy: "",
    disclaimer: "",
    publicEnabled: false,
    allowedDomains: [],
  };
}

export default function Personality({ stats }: { stats: Stats | null }) {
  const brainId = stats?.brain.id ?? null;
  const [profile, setProfile] = useState<BrainProfileDto | null>(null);
  const [draft, setDraft] = useState<BrainProfileRequest | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [rotating, setRotating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!brainId) {
      setProfile(null);
      setDraft(null);
      return;
    }
    setLoading(true);
    setError(null);
    setSaved(false);
    setToken(null);
    profileApi.get(brainId)
      .then((next) => {
        setProfile(next);
        setDraft(toRequest(next));
      })
      .catch((e) => {
        setError((e as Error).message);
        const fallback = defaultProfile(brainId);
        setProfile(fallback);
        setDraft(toRequest(fallback));
      })
      .finally(() => setLoading(false));
  }, [brainId]);

  const allowedDomainsText = useMemo(
    () => (draft ? draft.allowedDomains.join("\n") : ""),
    [draft],
  );

  if (!brainId) return <h1>Personality</h1>;
  if (!draft) return <h1>Personality</h1>;

  const set = <K extends keyof BrainProfileRequest>(key: K, value: BrainProfileRequest[K]) =>
    setDraft({ ...draft, [key]: value });

  async function save() {
    if (!profile?.brainId || !draft) return;
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      const updated = await profileApi.update(profile.brainId, draft);
      setProfile(updated);
      setDraft(toRequest(updated));
      setSaved(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function rotate() {
    if (!profile?.brainId) return;
    setRotating(true);
    setError(null);
    try {
      setToken((await profileApi.rotatePublicToken(profile.brainId)).token);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRotating(false);
    }
  }

  return (
    <>
      <header className="screen-head">
        <div>
          <h1>Personality</h1>
          <span className="muted">control public assistant behavior for the active brain</span>
        </div>
        <div className="chips">
          <Pill tone="gray">{stats?.brain.slug ?? "brain"}</Pill>
          <Pill tone="gray">{brainId.slice(0, 8)}</Pill>
          {loading && <Pill tone="gray">loading</Pill>}
        </div>
      </header>
      <ErrorNote message={error} />
      <div className="card">
        <div className="setting-row">
          <label>Mode</label>
          <select value={draft.mode} onChange={(e) => set("mode", e.target.value as BrainProfileRequest["mode"])}>
            <option value="PUBLIC_SITE">public website</option>
            <option value="PRIVATE_SITE">private website</option>
            <option value="SECURE_DEPLOYMENT">secure deployment</option>
          </select>
        </div>
        <div className="setting-row">
          <label>Purpose</label>
          <textarea value={draft.purpose} onChange={(e) => set("purpose", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Audience</label>
          <input value={draft.audience} onChange={(e) => set("audience", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Personality</label>
          <textarea value={draft.personality} onChange={(e) => set("personality", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Tone</label>
          <input value={draft.tone} onChange={(e) => set("tone", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Expertise level</label>
          <input value={draft.expertiseLevel} onChange={(e) => set("expertiseLevel", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Answer length</label>
          <input value={draft.answerLength} onChange={(e) => set("answerLength", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Confidence target</label>
          <input
            type="number"
            min="0"
            max="1"
            step="0.05"
            value={String(draft.confidenceTarget)}
            onChange={(e) => set("confidenceTarget", Number(e.target.value))}
          />
        </div>
        <div className="setting-row">
          <label>Clarification policy</label>
          <textarea value={draft.clarificationPolicy} onChange={(e) => set("clarificationPolicy", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Escalation policy</label>
          <textarea value={draft.escalationPolicy} onChange={(e) => set("escalationPolicy", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Citation policy</label>
          <input value={draft.citationPolicy} onChange={(e) => set("citationPolicy", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>CTA policy</label>
          <textarea value={draft.ctaPolicy} onChange={(e) => set("ctaPolicy", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Disclaimer</label>
          <textarea value={draft.disclaimer} onChange={(e) => set("disclaimer", e.target.value)} />
        </div>
        <div className="setting-row">
          <label>Public enabled</label>
          <select value={String(draft.publicEnabled)} onChange={(e) => set("publicEnabled", e.target.value === "true")}>
            <option value="true">enabled</option>
            <option value="false">disabled</option>
          </select>
        </div>
        <div className="setting-row">
          <label>Allowed domains</label>
          <textarea
            value={allowedDomainsText}
            placeholder="one host per line"
            onChange={(e) => set("allowedDomains", e.target.value
              .split("\n")
              .map((entry) => entry.trim().toLowerCase())
              .filter(Boolean))}
          />
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={save} disabled={saving}>
            {saving ? "Saving…" : "Save profile"}
          </button>
          <button onClick={rotate} disabled={rotating}>
            {rotating ? "Rotating…" : "Rotate public token"}
          </button>
          {saved && <Pill tone="green">saved</Pill>}
        </div>
        {token && <p className="muted">New public token: <code>{token}</code></p>}
      </div>
    </>
  );
}
