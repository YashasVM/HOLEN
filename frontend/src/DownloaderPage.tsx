import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useAuthActions } from "@convex-dev/auth/react";
import {
  CheckSquare,
  ChevronDown,
  Download,
  Film,
  Link2,
  List,
  Loader2,
  Lock,
  LogOut,
  Music,
  Settings,
  ShieldCheck,
  Square,
  X,
} from "lucide-react";

/* ── Types ───────────────────────────────────────────────── */

type Metadata = {
  title?: string;
  thumbnail?: string;
  duration?: number;
  uploader?: string;
  webpage_url: string;
  formats: Array<{
    format_id: string;
    ext?: string;
    resolution?: string;
    fps?: number;
    filesize?: number;
    vcodec?: string;
    acodec?: string;
    height?: number;
  }>;
  options: Array<{
    id: string;
    label: string;
    description: string;
    available: boolean;
    detail: string;
  }>;
};

type PlaylistEntry = {
  id: string;
  title: string;
  url: string;
  thumbnail?: string;
  duration?: number;
};

type PlaylistInfo = {
  title?: string;
  uploader?: string;
  entries: PlaylistEntry[];
};

type Job = {
  id: string;
  url: string;
  title?: string;
  thumbnail?: string;
  format: string;
  status: "queued" | "running" | "completed" | "failed" | "cancelled";
  progress: number;
  message?: string;
  file_name?: string;
  download_url?: string;
  created_at: string;
};

const FORMAT_OPTIONS = [
  { id: "best",  label: "4K / Best MP4" },
  { id: "1080p", label: "1080p MP4" },
  { id: "720p",  label: "720p MP4" },
  { id: "audio", label: "Audio (best)" },
  { id: "mp3",   label: "MP3" },
];

/* ── Helpers ─────────────────────────────────────────────── */

function fmtDuration(s?: number): string {
  if (!s) return "";
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(Math.floor(s % 60)).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

function extractVideoId(url: string): string | null {
  try {
    const u = new URL(url);
    if (u.hostname.includes("youtu.be")) return u.pathname.slice(1);
    return u.searchParams.get("v");
  } catch { return null; }
}

function getJobThumbnail(job: Job): string | null {
  if (job.thumbnail) return job.thumbnail;
  const vid = extractVideoId(job.url);
  if (vid) return `https://i.ytimg.com/vi/${vid}/mqdefault.jpg`;
  return null;
}

function isPlaylistUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.searchParams.has("list") || u.pathname.startsWith("/playlist");
  } catch { return false; }
}

/* ── Props ───────────────────────────────────────────────── */

interface DownloaderPageProps {
  user: { name?: string; email?: string; isAdmin: boolean };
  onAdminClick: () => void;
}

/* ── Component ───────────────────────────────────────────── */

export function DownloaderPage({ user, onAdminClick }: DownloaderPageProps) {
  const { signOut } = useAuthActions();
  const [token, setToken] = useState(() => localStorage.getItem("downloader_token") || "");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [url, setUrl] = useState("");
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [playlist, setPlaylist] = useState<PlaylistInfo | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectedFormat, setSelectedFormat] = useState("best");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [selectedJobs, setSelectedJobs] = useState<Set<string>>(new Set());
  const sseRef = useRef<EventSource | null>(null);

  const headers = useMemo(() => ({
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  }), [token]);

  async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
    const r = await fetch(path, { ...init, headers: { ...headers, ...(init?.headers || {}) } });
    if (r.status === 401) { localStorage.removeItem("downloader_token"); setToken(""); }
    if (!r.ok) { const b = await r.json().catch(() => ({})); throw new Error(b.detail || "Request failed"); }
    return r.json();
  }

  async function backendLogin(e: FormEvent) {
    e.preventDefault();
    setAuthError("");
    try {
      const r = await fetch("/api/session/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password }) });
      if (!r.ok) throw new Error("Wrong password");
      const { token: t } = (await r.json()) as { token: string };
      localStorage.setItem("downloader_token", t);
      setToken(t);
      setPassword("");
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : "Login failed");
    }
  }

  async function analyze(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMetadata(null);
    setPlaylist(null);
    setSelectedIds(new Set());
    try {
      if (isPlaylistUrl(url)) {
        const pl = await apiFetch<PlaylistInfo>("/api/playlist", { method: "POST", body: JSON.stringify({ url }) });
        setPlaylist(pl);
        setSelectedIds(new Set(pl.entries.map((e) => e.id)));
      } else {
        setMetadata(await apiFetch<Metadata>("/api/analyze", { method: "POST", body: JSON.stringify({ url }) }));
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Preview failed");
    } finally {
      setBusy(false);
    }
  }

  async function createJob(jobUrl: string, format: string, title?: string, thumbnail?: string) {
    return apiFetch<Job>("/api/jobs", {
      method: "POST",
      body: JSON.stringify({ url: jobUrl, format, title, thumbnail }),
    });
  }

  async function handleDownload() {
    if (!metadata) return;
    setBusy(true);
    setError("");
    try {
      const job = await createJob(metadata.webpage_url, selectedFormat, metadata.title, metadata.thumbnail);
      setJobs((cur) => [job, ...cur.filter((j) => j.id !== job.id)]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Download failed");
    } finally {
      setBusy(false);
    }
  }

  async function handlePlaylistDownload() {
    if (!playlist) return;
    setBusy(true);
    setError("");
    const selected = playlist.entries.filter((e) => selectedIds.has(e.id));
    let lastErr = "";
    for (const entry of selected) {
      try {
        const job = await createJob(entry.url, selectedFormat, entry.title, entry.thumbnail);
        setJobs((cur) => [job, ...cur.filter((j) => j.id !== job.id)]);
      } catch (err) {
        lastErr = err instanceof Error ? err.message : "Failed";
      }
    }
    if (lastErr) setError(lastErr);
    setBusy(false);
  }

  async function cancelJob(jobId: string) {
    setJobs((cur) => cur.map((j) => j.id === jobId ? { ...j, status: "cancelled" as const } : j));
    try { await apiFetch(`/api/jobs/${jobId}`, { method: "DELETE" }); } catch { /* SSE corrects */ }
  }

  async function deleteSelectedJobs() {
    const ids = [...selectedJobs];
    setJobs((cur) => cur.filter((j) => !selectedJobs.has(j.id) || j.status === "running" || j.status === "queued"));
    setSelectedJobs(new Set());
    try { await apiFetch("/api/jobs", { method: "DELETE", body: JSON.stringify({ ids }) }); } catch { /* SSE corrects */ }
  }

  function toggleJobSelection(jobId: string) {
    setSelectedJobs((prev) => { const n = new Set(prev); n.has(jobId) ? n.delete(jobId) : n.add(jobId); return n; });
  }

  async function downloadFile(job: Job) {
    if (!job.download_url) return;
    const url = `${job.download_url}?token=${encodeURIComponent(token)}`;
    const a = document.createElement("a");
    a.href = url;
    a.download = job.file_name || "download";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  useEffect(() => {
    if (!token) return;
    apiFetch<Job[]>("/api/jobs").then(setJobs).catch(() => setJobs([]));
    const es = new EventSource(`/api/jobs/stream?token=${encodeURIComponent(token)}`);
    sseRef.current = es;
    es.onmessage = (e) => { try { setJobs(JSON.parse(e.data) as Job[]); } catch { /* ignore */ } };
    return () => { es.close(); sseRef.current = null; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  function toggleEntry(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!playlist) return;
    const allIds = new Set(playlist.entries.map((e) => e.id));
    setSelectedIds(allIds.size === selectedIds.size ? new Set() : allIds);
  }

  /* ── App Password Gate ──────────────────────────────────── */

  if (!token) {
    return (
      <main className="auth-shell">
        <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
        <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
        <div className="bauhaus-deco bauhaus-triangle" aria-hidden="true" />
        <div className="bauhaus-deco bauhaus-line" aria-hidden="true" />
        <form className="auth-card slide-up" onSubmit={backendLogin}>
          <div className="auth-corner" aria-hidden="true" />
          <div className="logo-row">
            <div className="logo-circle logo-circle-green"><ShieldCheck size={20} /></div>
            <div>
              <span className="header-tag header-tag-green">Final step</span>
              <h1>App Password</h1>
            </div>
          </div>
          <p className="auth-subtitle">Enter the shared application password to unlock the downloader.</p>
          <div className="field">
            <label className="field-label" htmlFor="app-pw">Password</label>
            <div className="input-box">
              <Lock size={16} />
              <input id="app-pw" value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete="off" placeholder="Enter app password" required />
            </div>
          </div>
          {authError && <p className="error-msg fade-in" role="alert">{authError}</p>}
          <button className="btn btn-primary" type="submit" disabled={!password}><ShieldCheck size={16} /> Unlock</button>
          <button className="btn btn-dark" type="button" onClick={() => signOut()} style={{ marginTop: 10 }}><LogOut size={14} /> Sign Out</button>
        </form>
      </main>
    );
  }

  /* ── Main Downloader UI ─────────────────────────────────── */

  const allIds = playlist ? new Set(playlist.entries.map((e) => e.id)) : new Set<string>();
  const allSelected = playlist ? allIds.size === selectedIds.size : false;

  return (
    <main className="app-shell fade-in">
      {/* Header */}
      <header className="header">
        <div className="header-left">
          <div className="header-badge">Y</div>
          <div className="header-text">
            <span className="header-tag">Private node</span>
            <h1>Downloader</h1>
          </div>
        </div>
        <div className="header-actions">
          {user.isAdmin && (
            <button className="btn btn-outline" type="button" onClick={onAdminClick}>
              <Settings size={14} /> Admin
            </button>
          )}
          <button className="btn btn-dark" type="button" onClick={() => { localStorage.removeItem("downloader_token"); setToken(""); }}>
            <Lock size={14} /> Lock
          </button>
        </div>
      </header>

      {/* URL Input */}
      <section className="url-card">
        <form className="url-form" onSubmit={analyze}>
          <div className="field">
            <label className="field-label" htmlFor="src-url">Paste URL</label>
            <div className="input-box">
              <Link2 size={16} />
              <input id="src-url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://youtube.com/watch?v=... or playlist" type="url" autoComplete="off" spellCheck={false} />
            </div>
          </div>
          <button className="btn btn-primary" type="submit" disabled={busy || !url} style={{ width: "auto", minWidth: 130 }}>
            {busy ? <Loader2 className="spin" size={16} /> : <Download size={16} />} Analyze
          </button>
        </form>

        {error && <p className="error-msg fade-in" role="alert">{error}</p>}

        {/* Single Video Preview */}
        {metadata && (
          <div className="preview-card fade-in">
            {metadata.thumbnail && <img className="preview-thumb" src={metadata.thumbnail} alt="" />}
            <div className="preview-info">
              <span className="header-tag">{metadata.uploader || "Source"}</span>
              <h2>{metadata.title || "Untitled"}</h2>
              <div className="meta-chips">
                {metadata.duration ? <span className="meta-chip">{fmtDuration(metadata.duration)}</span> : null}
                <span className="meta-chip">{metadata.formats.length} formats</span>
              </div>
              <div className="format-row">
                <div className="select-wrapper">
                  <select className="format-select" value={selectedFormat} onChange={(e) => setSelectedFormat(e.target.value)}>
                    {FORMAT_OPTIONS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
                  </select>
                  <ChevronDown size={14} className="select-chevron" />
                </div>
                <button className="btn btn-primary" type="button" onClick={handleDownload} disabled={busy}>
                  {busy ? <Loader2 className="spin" size={14} /> : <Download size={14} />} Download
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Playlist Picker */}
        {playlist && (
          <div className="playlist-picker fade-in">
            <div className="playlist-header">
              <div className="playlist-meta">
                <List size={16} />
                <strong>{playlist.title || "Playlist"}</strong>
                {playlist.uploader && <span className="muted">by {playlist.uploader}</span>}
                <span className="meta-chip">{playlist.entries.length} videos</span>
              </div>
              <div className="format-row">
                <div className="select-wrapper">
                  <select className="format-select" value={selectedFormat} onChange={(e) => setSelectedFormat(e.target.value)}>
                    {FORMAT_OPTIONS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
                  </select>
                  <ChevronDown size={14} className="select-chevron" />
                </div>
                <button className="btn btn-primary" type="button" onClick={handlePlaylistDownload} disabled={busy || selectedIds.size === 0}>
                  {busy ? <Loader2 className="spin" size={14} /> : <Download size={14} />}
                  {selectedIds.size > 0 ? `Queue ${selectedIds.size}` : "Queue"}
                </button>
              </div>
            </div>

            <button className="playlist-select-all" type="button" onClick={toggleAll}>
              {allSelected ? <CheckSquare size={15} /> : <Square size={15} />}
              {allSelected ? "Deselect all" : "Select all"}
            </button>

            <div className="playlist-entries">
              {playlist.entries.map((entry) => {
                const checked = selectedIds.has(entry.id);
                return (
                  <div className={`playlist-entry ${checked ? "selected" : ""}`} key={entry.id} onClick={() => toggleEntry(entry.id)}>
                    <span className="entry-check">{checked ? <CheckSquare size={16} /> : <Square size={16} />}</span>
                    {entry.thumbnail
                      ? <img className="entry-thumb" src={entry.thumbnail} alt="" loading="lazy" />
                      : <div className="entry-thumb-placeholder"><Film size={14} /></div>}
                    <span className="entry-title">{entry.title}</span>
                    {entry.duration ? <span className="entry-dur muted">{fmtDuration(entry.duration)}</span> : null}
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </section>

      {/* Job Queue */}
      <section className="queue-section">
        <div className="queue-header">
          <span className="header-tag">Queue</span>
          <h2>{jobs.length} {jobs.length === 1 ? "Job" : "Jobs"}</h2>
          {jobs.some((j) => j.status !== "running" && j.status !== "queued") && (
            <div className="queue-actions">
              <button className="btn btn-outline" type="button" onClick={() => {
                const clearable = jobs.filter((j) => j.status !== "running" && j.status !== "queued").map((j) => j.id);
                setSelectedJobs((prev) => prev.size === clearable.length && clearable.every((id) => prev.has(id)) ? new Set() : new Set(clearable));
              }}>
                {(() => {
                  const clearable = jobs.filter((j) => j.status !== "running" && j.status !== "queued");
                  const allSel = clearable.length > 0 && clearable.every((j) => selectedJobs.has(j.id));
                  return allSel ? <><CheckSquare size={14} /> Deselect All</> : <><Square size={14} /> Select All</>;
                })()}
              </button>
              {selectedJobs.size > 0 && (
                <button className="btn btn-danger" type="button" onClick={deleteSelectedJobs}>
                  <X size={14} /> Clear {selectedJobs.size}
                </button>
              )}
            </div>
          )}
        </div>
        <div className="job-list">
          {jobs.map((job) => {
            const thumb = getJobThumbnail(job);
            const canCancel = job.status === "queued" || job.status === "running";
            const canSelect = !canCancel;
            const isAudio = job.format === "audio" || job.format === "mp3";
            const isChecked = selectedJobs.has(job.id);
            return (
              <article className={`job-card${isChecked ? " job-card-selected" : ""}`} key={job.id}>
                <div className="job-card-inner">
                  {canSelect && (
                    <button className="job-check" type="button" onClick={() => toggleJobSelection(job.id)} aria-label="Select job">
                      {isChecked ? <CheckSquare size={16} /> : <Square size={16} />}
                    </button>
                  )}
                  {thumb
                    ? <img className="job-thumb" src={thumb} alt="" loading="lazy" />
                    : <div className="job-thumb-placeholder">{isAudio ? <Music size={20} /> : <Film size={20} />}</div>}
                  <div className="job-content">
                    <div className="job-top">
                      <span className={`badge ${job.status}`}>{job.status}</span>
                      <strong>{job.title || job.file_name || job.url}</strong>
                      {canCancel && (
                        <button className="btn btn-dark" type="button" title="Cancel" onClick={() => cancelJob(job.id)} style={{ marginLeft: "auto", padding: "2px 6px", minWidth: 0 }}>
                          <X size={12} />
                        </button>
                      )}
                    </div>
                    <div className="progress-bar">
                      <div className="progress-fill" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(Math.max(0, Math.min(100, job.progress)))} style={{ width: `${Math.max(0, Math.min(100, job.progress))}%` }} />
                    </div>
                    <div className="job-bottom">
                      <span>{job.message || job.format}</span>
                    </div>
                  </div>
                </div>
                {job.download_url && (
                  <button className="download-btn" type="button" onClick={() => downloadFile(job)}>
                    <Download size={16} />
                    <span>Download</span>
                  </button>
                )}
              </article>
            );
          })}
          {!jobs.length && <p className="empty-msg">No jobs yet — paste a URL above to start.</p>}
        </div>
      </section>

      <footer className="watermark">
        made by{" "}
        <a href="https://github.com/YashasVM" target="_blank" rel="noopener noreferrer">@yashas.vm</a>
      </footer>
    </main>
  );
}
