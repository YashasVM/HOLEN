import React, { useCallback, useEffect, useMemo, useRef, useState, FormEvent } from "react";
import { UserButton } from "@clerk/react";
import {
  CheckSquare,
  ChevronDown,
  Copy,
  Download,
  Film,
  Link2,
  List,
  Loader2,
  Music,
  Settings,
  Square,
  Trash2,
  X,
} from "lucide-react";
import type { AppUser } from "./types";

/* ── Types ───────────────────────────────────────────────── */

type Metadata = {
  title?: string;
  thumbnail?: string;
  duration?: number;
  uploader?: string;
  webpage_url: string;
  formats: Array<{
    format_id: string; ext?: string; resolution?: string; fps?: number;
    filesize?: number; vcodec?: string; acodec?: string; height?: number;
  }>;
  options: Array<{ id: string; label: string; description: string; available: boolean; detail: string }>;
};

type PlaylistEntry = { id: string; title: string; url: string; thumbnail?: string; duration?: number };
type PlaylistInfo = { title?: string; uploader?: string; entries: PlaylistEntry[] };

type Job = {
  id: string; url: string; title?: string; thumbnail?: string; format: string;
  status: "queued" | "running" | "completed" | "failed" | "cancelled";
  progress: number; message?: string; file_name?: string; download_url?: string; created_at: string;
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
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  const sec = String(Math.floor(s % 60)).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

function fmtBytes(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** unit).toFixed(unit < 2 ? 0 : 1)} ${units[unit]}`;
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

// Human-readable error messages for common API errors
function friendlyError(msg: string): string {
  if (msg.includes("Rate limit")) return "Slow down — too many requests. Try again in a moment.";
  if (msg.includes("Queue is full")) return "The queue is full. Wait for a job to finish, then try again.";
  if (msg.includes("already have")) return msg; // per-user limit message is already friendly
  if (msg.includes("Wrong password")) return "Incorrect password. Try again.";
  if (msg.includes("Token expired")) return "Session expired — please re-enter the app password.";
  if (msg.includes("storage is full")) return "Server storage is full. Ask an admin to clear some files.";
  if (msg.includes("minutes or shorter")) return msg;
  if (msg.includes("Only YouTube")) return "Only YouTube URLs are supported.";
  if (msg.includes("valid http")) return "Please enter a valid URL (https://youtube.com/...).";
  if (msg.includes("Metadata lookup failed") || msg.includes("Preview failed")) return "Couldn't fetch video info. Check the URL or try again.";
  return msg || "Something went wrong. Please try again.";
}

/* ── Props ───────────────────────────────────────────────── */

interface DownloaderPageProps {
  user: AppUser;
  token: string;
  onAdminClick: () => void;
}

/* ── Toast ───────────────────────────────────────────────── */

type Toast = { id: number; msg: string; ok: boolean };
let _toastId = 0;

function useToast() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const show = useCallback((msg: string, ok = true) => {
    const id = ++_toastId;
    setToasts((t) => [...t, { id, msg, ok }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500);
  }, []);
  return { toasts, show };
}

/* ── Component ───────────────────────────────────────────── */

export function DownloaderPage({ user, token, onAdminClick }: DownloaderPageProps) {
  // Use sessionStorage so the token is cleared when the tab closes (XSS mitigation)
  const [url, setUrl] = useState("");
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [playlist, setPlaylist] = useState<PlaylistInfo | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectedFormat, setSelectedFormat] = useState("best");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [selectedJobs, setSelectedJobs] = useState<Set<string>>(new Set());
  const [usage, setUsage] = useState(user);
  const { toasts, show: showToast } = useToast();
  const sseRef = useRef<EventSource | null>(null);
  const completedJobIdsRef = useRef<Set<string>>(new Set());

  const headers = useMemo(() => ({
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
  }), [token]);

  const apiFetch = useCallback(async <T,>(path: string, init?: RequestInit): Promise<T> => {
    const r = await fetch(path, { ...init, headers: { ...headers, ...(init?.headers || {}) } });
    if (!r.ok) {
      const b = await r.json().catch(() => ({}));
      throw new Error(b.detail || "Request failed");
    }
    return r.json();
  }, [headers]);

  const refreshUsage = useCallback(async () => {
    try {
      setUsage(await apiFetch<AppUser>("/api/me"));
    } catch {
      // The next regular profile refresh will retry if this transient request fails.
    }
  }, [apiFetch]);

  useEffect(() => { setUsage(user); }, [user]);

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
      setError(friendlyError(err instanceof Error ? err.message : "Preview failed"));
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
      showToast("Added to queue");
      setMetadata(null);
      setUrl("");
    } catch (err) {
      setError(friendlyError(err instanceof Error ? err.message : "Download failed"));
    } finally {
      setBusy(false);
    }
  }

  async function handlePlaylistDownload() {
    if (!playlist) return;
    setBusy(true);
    setError("");
    const selected = playlist.entries.filter((e) => selectedIds.has(e.id));
    let failed = 0;
    for (const entry of selected) {
      try {
        const job = await createJob(entry.url, selectedFormat, entry.title, entry.thumbnail);
        setJobs((cur) => [job, ...cur.filter((j) => j.id !== job.id)]);
      } catch {
        failed++;
      }
    }
    if (failed > 0) setError(`${failed} video(s) failed to queue. Check limits and try again.`);
    else { showToast(`Queued ${selected.length} video(s)`); setPlaylist(null); setUrl(""); }
    setBusy(false);
  }

  async function cancelJob(jobId: string) {
    setJobs((cur) => cur.map((j) => j.id === jobId ? { ...j, status: "cancelled" as const } : j));
    try { await apiFetch(`/api/jobs/${jobId}`, { method: "DELETE" }); showToast("Job cancelled"); }
    catch { /* SSE corrects */ }
  }

  async function deleteSelectedJobs() {
    const ids = [...selectedJobs];
    setJobs((cur) => cur.filter((j) => !selectedJobs.has(j.id) || j.status === "running" || j.status === "queued"));
    setSelectedJobs(new Set());
    try {
      const result = await apiFetch<{ deleted: number }>("/api/jobs", { method: "DELETE", body: JSON.stringify({ ids }) });
      showToast(`Cleared ${result.deleted} job(s)`);
    } catch { /* SSE corrects */ }
  }

  async function clearAllCompleted() {
    const clearable = jobs.filter((j) => j.status !== "running" && j.status !== "queued");
    if (clearable.length === 0) return;
    const ids = clearable.map((j) => j.id);
    setJobs((cur) => cur.filter((j) => j.status === "running" || j.status === "queued"));
    setSelectedJobs(new Set());
    try {
      const result = await apiFetch<{ deleted: number }>("/api/jobs", { method: "DELETE", body: JSON.stringify({ ids }) });
      showToast(`Cleared ${result.deleted} job(s)`);
    } catch { /* SSE corrects */ }
  }

  function toggleJobSelection(jobId: string) {
    setSelectedJobs((prev) => { const n = new Set(prev); n.has(jobId) ? n.delete(jobId) : n.add(jobId); return n; });
  }

  async function downloadFile(job: Job) {
    if (!job.download_url) return;
    const dlUrl = `${job.download_url}?token=${encodeURIComponent(token)}`;
    const a = document.createElement("a");
    a.href = dlUrl;
    a.download = job.file_name || "download";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.setTimeout(() => void refreshUsage(), 750);
  }

  async function copyJobLink(job: Job) {
    if (!job.download_url) return;
    const fullUrl = `${window.location.origin}${job.download_url}?token=${encodeURIComponent(token)}`;
    try {
      await navigator.clipboard.writeText(fullUrl);
      showToast("Download link copied!");
    } catch {
      showToast("Couldn't copy link", false);
    }
  }

  useEffect(() => {
    if (!token) return;
    apiFetch<Job[]>("/api/jobs").then(setJobs).catch(() => setJobs([]));
    void refreshUsage();
    const es = new EventSource(`/api/jobs/stream?token=${encodeURIComponent(token)}`);
    sseRef.current = es;
    es.onmessage = (e) => {
      try {
        const nextJobs = JSON.parse(e.data) as Job[];
        setJobs(nextJobs);
        const justCompleted = nextJobs.some((job) => job.status === "completed" && !completedJobIdsRef.current.has(job.id));
        nextJobs.filter((job) => job.status === "completed").forEach((job) => completedJobIdsRef.current.add(job.id));
        if (justCompleted) void refreshUsage();
      } catch { /* ignore malformed stream messages */ }
    };
    es.onerror = () => {
      // SSE disconnect — quietly reconnect on next render cycle
      es.close();
      sseRef.current = null;
    };
    return () => { es.close(); sseRef.current = null; };
  }, [apiFetch, refreshUsage, token]);

  function toggleEntry(id: string) {
    setSelectedIds((prev) => { const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next; });
  }

  function toggleAll() {
    if (!playlist) return;
    const allIds = new Set(playlist.entries.map((e) => e.id));
    setSelectedIds(allIds.size === selectedIds.size ? new Set() : allIds);
  }

  /* ── Derived state ──────────────────────────────────────── */
  const activeJobs = jobs.filter((j) => j.status === "running" || j.status === "queued");
  const completedJobs = jobs.filter((j) => j.status === "completed");
  const doneJobs = jobs.filter((j) => j.status !== "running" && j.status !== "queued");
  const allIds = playlist ? new Set(playlist.entries.map((e) => e.id)) : new Set<string>();
  const allSelected = playlist ? allIds.size === selectedIds.size : false;
  const clearableCount = doneJobs.length;

  /* ── Main Downloader UI ─────────────────────────────────── */

  return (
    <main className="app-shell fade-in">
      {/* Toasts */}
      <div className="toast-container" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.ok ? "toast-ok" : "toast-err"} slide-up`}>{t.msg}</div>
        ))}
      </div>

      {/* Header */}
      <header className="header">
        <div className="header-left">
          <div className="header-badge">Y</div>
          <div className="header-text">
            <span className="header-tag">Private node</span>
            <h1>Downloader</h1>
          </div>
          {activeJobs.length > 0 && (
            <span className="job-count-badge" title={`${activeJobs.length} active job(s)`}>
              {activeJobs.length}
            </span>
          )}
        </div>
        <div className="header-actions">
          <div className="usage-chip" title="Downloads into the server and downloads to your device both count">
            <span>Bandwidth</span>
            <strong>{fmtBytes(usage.used_bytes)} / {fmtBytes(usage.usage_limit_bytes)}</strong>
          </div>
          {user.is_admin && (
            <button className="btn btn-outline" type="button" onClick={onAdminClick}>
              <Settings size={14} /> Admin
            </button>
          )}
          <UserButton appearance={{ elements: { avatarBox: "clerk-avatar", avatarImage: "clerk-avatar-image" } }} />
        </div>
      </header>

      {/* URL Input */}
      <section className="url-card">
        <form className="url-form" onSubmit={analyze}>
          <div className="field">
            <label className="field-label" htmlFor="src-url">Paste URL</label>
            <div className="input-box">
              <Link2 size={16} />
              <input
                id="src-url" value={url}
                onChange={(e) => { setUrl(e.target.value); setError(""); setMetadata(null); setPlaylist(null); }}
                placeholder="https://youtube.com/watch?v=... or playlist"
                type="url" autoComplete="off" spellCheck={false}
              />
              {url && (
                <button type="button" className="input-clear" aria-label="Clear URL" onClick={() => { setUrl(""); setMetadata(null); setPlaylist(null); setError(""); }}>
                  <X size={14} />
                </button>
              )}
            </div>
          </div>
          <button className="btn btn-primary" type="submit" disabled={busy || !url} style={{ width: "auto", minWidth: 130 }}>
            {busy ? <Loader2 className="spin" size={16} /> : <Download size={16} />} Analyze
          </button>
        </form>

        {error && (
          <p className="error-msg fade-in" role="alert">
            {error}
          </p>
        )}

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
          <h2>
            {jobs.length} {jobs.length === 1 ? "Job" : "Jobs"}
            {completedJobs.length > 0 && <span className="queue-ready-badge">{completedJobs.length} ready</span>}
          </h2>
          <div className="queue-actions">
            {clearableCount > 0 && selectedJobs.size === 0 && (
              <button className="btn btn-outline" type="button" onClick={clearAllCompleted} title="Clear all finished jobs">
                <Trash2 size={14} /> Clear {clearableCount}
              </button>
            )}
            {clearableCount > 0 && (
              <button className="btn btn-outline" type="button" onClick={() => {
                const ids = new Set(doneJobs.map((j) => j.id));
                setSelectedJobs((prev) => prev.size === ids.size && [...ids].every((id) => prev.has(id)) ? new Set() : ids);
              }}>
                {doneJobs.every((j) => selectedJobs.has(j.id)) && doneJobs.length > 0
                  ? <><CheckSquare size={14} /> Deselect</>
                  : <><Square size={14} /> Select</>}
              </button>
            )}
            {selectedJobs.size > 0 && (
              <button className="btn btn-danger" type="button" onClick={deleteSelectedJobs}>
                <X size={14} /> Clear {selectedJobs.size}
              </button>
            )}
          </div>
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
                      <strong className="job-title" title={job.title || job.url}>{job.title || job.file_name || job.url}</strong>
                      {canCancel && (
                        <button className="btn btn-dark" type="button" title="Cancel" onClick={() => cancelJob(job.id)} style={{ marginLeft: "auto", padding: "2px 6px", minWidth: 0 }}>
                          <X size={12} />
                        </button>
                      )}
                    </div>
                    {(job.status === "running" || job.status === "queued") && (
                      <div className="progress-bar">
                        <div
                          className="progress-fill"
                          role="progressbar"
                          aria-valuemin={0} aria-valuemax={100}
                          aria-valuenow={Math.round(Math.max(0, Math.min(100, job.progress)))}
                          style={{ transform: `scaleX(${Math.max(0, Math.min(100, job.progress)) / 100})` }}
                        />
                      </div>
                    )}
                    <div className="job-bottom">
                      <span className="job-msg">{job.status === "failed" ? `✗ ${job.message || "Failed"}` : job.message || job.format}</span>
                    </div>
                  </div>
                </div>
                {job.download_url && (
                  <div className="job-download-row">
                    <button className="download-btn" type="button" onClick={() => downloadFile(job)}>
                      <Download size={16} /><span>Download</span>
                    </button>
                    <button className="copy-link-btn" type="button" title="Copy download link" onClick={() => copyJobLink(job)}>
                      <Copy size={14} />
                    </button>
                  </div>
                )}
              </article>
            );
          })}
          {!jobs.length && (
            <div className="empty-state">
              <Download size={32} className="empty-icon" />
              <p>No jobs yet — paste a YouTube URL above to start.</p>
            </div>
          )}
        </div>
      </section>

      <footer className="watermark">
        made by{" "}
        <a href="https://github.com/YashasVM" target="_blank" rel="noopener noreferrer">@yashas.vm</a>
      </footer>
    </main>
  );
}
