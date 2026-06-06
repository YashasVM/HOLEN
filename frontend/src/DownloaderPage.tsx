import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { useAuthActions } from "@convex-dev/auth/react";
import {
  Download,
  FileAudio,
  Film,
  Link2,
  Loader2,
  Lock,
  LogOut,
  MonitorUp,
  Music,
  Play,
  Settings,
  ShieldCheck,
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
  }>;
  options: Array<{
    id: "best" | "1080p" | "720p" | "audio";
    label: string;
    description: string;
    available: boolean;
    detail: string;
  }>;
};

type Job = {
  id: string;
  url: string;
  title?: string;
  format: "best" | "best_video" | "1080p" | "720p" | "audio" | "mp3";
  status: "queued" | "running" | "completed" | "failed";
  progress: number;
  message?: string;
  file_name?: string;
  download_url?: string;
  created_at: string;
};

const FMT_ICONS: Record<string, typeof Film> = {
  best: Film,
  "1080p": MonitorUp,
  "720p": Play,
  audio: FileAudio,
  mp3: Music,
};

/* ── Helpers ─────────────────────────────────────────────── */

function fmtDuration(s?: number): string {
  if (!s) return "\u2014";
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(Math.floor(s % 60)).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

function fmtBytes(b?: number): string {
  if (!b) return "\u2014";
  const units = ["B", "KB", "MB", "GB"];
  let val = b;
  let idx = 0;
  while (val >= 1024 && idx < units.length - 1) {
    val /= 1024;
    idx++;
  }
  return `${val.toFixed(idx === 0 ? 0 : 1)} ${units[idx]}`;
}

/* ── Props ───────────────────────────────────────────────── */

interface DownloaderPageProps {
  user: { name?: string; email?: string; isAdmin: boolean };
  onAdminClick: () => void;
}

/* ── Component ───────────────────────────────────────────── */

export function DownloaderPage({ user, onAdminClick }: DownloaderPageProps) {
  const { signOut } = useAuthActions();
  const [token, setToken] = useState(
    () => localStorage.getItem("downloader_token") || "",
  );
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [url, setUrl] = useState("");
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const headers = useMemo(
    () => ({
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    }),
    [token],
  );

  /* ── API helper ─────────────────────────────────────── */

  async function api<T>(path: string, init?: RequestInit): Promise<T> {
    const r = await fetch(path, {
      ...init,
      headers: { ...headers, ...(init?.headers || {}) },
    });
    if (r.status === 401) {
      localStorage.removeItem("downloader_token");
      setToken("");
    }
    if (!r.ok) {
      const b = await r.json().catch(() => ({}));
      throw new Error(b.detail || "Request failed");
    }
    return r.json();
  }

  /* ── Backend password login ─────────────────────────── */

  async function backendLogin(e: FormEvent) {
    e.preventDefault();
    setAuthError("");
    try {
      const r = await fetch("/api/session/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      if (!r.ok) throw new Error("Wrong password");
      const { token: t } = (await r.json()) as { token: string };
      localStorage.setItem("downloader_token", t);
      setToken(t);
      setPassword("");
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : "Login failed");
    }
  }

  /* ── Analyze URL ────────────────────────────────────── */

  async function preview(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMetadata(null);
    try {
      setMetadata(
        await api<Metadata>("/api/analyze", {
          method: "POST",
          body: JSON.stringify({ url }),
        }),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Preview failed");
    } finally {
      setBusy(false);
    }
  }

  /* ── Create download job ────────────────────────────── */

  async function createJob(format: Job["format"]) {
    if (!metadata) return;
    setBusy(true);
    setError("");
    try {
      const job = await api<Job>("/api/jobs", {
        method: "POST",
        body: JSON.stringify({ url: metadata.webpage_url, format }),
      });
      setJobs((cur) => [job, ...cur.filter((j) => j.id !== job.id)]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Download failed");
    } finally {
      setBusy(false);
    }
  }

  /* ── Poll job status ────────────────────────────────── */

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    const load = async () => {
      try {
        const j = await api<Job[]>("/api/jobs");
        if (!cancelled) setJobs(j);
      } catch {
        if (!cancelled) setJobs([]);
      }
    };
    load();
    const interval = setInterval(load, 2000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  /* ── Download options ───────────────────────────────── */

  const dlOptions = useMemo(() => {
    if (!metadata) return [];
    const list = [...metadata.options];
    if (
      metadata.formats.some((f) => f.acodec !== "none" && f.acodec != null)
    ) {
      list.push({
        id: "mp3" as "best",
        label: "MP3",
        description: "Extract audio as MP3",
        available: true,
        detail: "Best quality",
      });
    }
    return list;
  }, [metadata]);

  /* ── App Password Gate ─────────────────────────────── */

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
            <div className="logo-circle logo-circle-green">
              <ShieldCheck size={20} />
            </div>
            <div>
              <span className="header-tag header-tag-green">Final step</span>
              <h1>App Password</h1>
            </div>
          </div>

          <p className="auth-subtitle">
            Enter the shared application password to unlock the downloader.
          </p>

          <div className="field">
            <label className="field-label" htmlFor="app-pw">
              Password
            </label>
            <div className="input-box">
              <Lock size={16} />
              <input
                id="app-pw"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                type="password"
                autoComplete="off"
                placeholder="Enter app password"
                required
              />
            </div>
          </div>

          {authError && (
            <p className="error-msg fade-in" role="alert">
              {authError}
            </p>
          )}

          <button
            className="btn btn-primary"
            type="submit"
            disabled={!password}
          >
            <ShieldCheck size={16} /> Unlock
          </button>

          <button
            className="btn btn-dark"
            type="button"
            onClick={() => signOut()}
            style={{ marginTop: 10 }}
          >
            <LogOut size={14} /> Sign Out
          </button>
        </form>
      </main>
    );
  }

  /* ── Main Downloader UI ────────────────────────────── */

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
            <button
              className="btn btn-outline"
              type="button"
              onClick={onAdminClick}
            >
              <Settings size={14} /> Admin
            </button>
          )}
          <button
            className="btn btn-dark"
            type="button"
            onClick={() => {
              localStorage.removeItem("downloader_token");
              setToken("");
            }}
          >
            <Lock size={14} /> Lock
          </button>
        </div>
      </header>

      {/* URL Input Section */}
      <section className="url-card">
        <form className="url-form" onSubmit={preview}>
          <div className="field">
            <label className="field-label" htmlFor="src-url">
              Paste URL
            </label>
            <div className="input-box">
              <Link2 size={16} />
              <input
                id="src-url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://youtube.com/watch?v=..."
                type="url"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
          </div>
          <button
            className="btn btn-primary"
            type="submit"
            disabled={busy || !url}
            style={{ width: "auto", minWidth: 130 }}
          >
            {busy ? (
              <Loader2 className="spin" size={16} />
            ) : (
              <Download size={16} />
            )}{" "}
            Analyze
          </button>
        </form>

        {error && (
          <p className="error-msg fade-in" role="alert">
            {error}
          </p>
        )}

        {/* Video Preview */}
        {metadata && (
          <div className="preview-card fade-in">
            {metadata.thumbnail && (
              <img
                className="preview-thumb"
                src={metadata.thumbnail}
                alt=""
              />
            )}
            <div className="preview-info">
              <span className="header-tag">
                {metadata.uploader || "Source"}
              </span>
              <h2>{metadata.title || "Untitled"}</h2>
              <div className="meta-chips">
                <span className="meta-chip">
                  {fmtDuration(metadata.duration)}
                </span>
                <span className="meta-chip">
                  {metadata.formats.length} formats
                </span>
              </div>
              <div className="format-buttons">
                {dlOptions.map((opt) => {
                  const Icon = FMT_ICONS[opt.id] || FileAudio;
                  return (
                    <button
                      key={opt.id}
                      className="fmt-btn"
                      type="button"
                      onClick={() => createJob(opt.id as Job["format"])}
                      disabled={busy || !opt.available}
                      title={opt.description}
                    >
                      <Icon size={15} />
                      <span>
                        <strong>{opt.label}</strong>
                        <small>{opt.detail}</small>
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        )}

        {/* Format Table */}
        {metadata && (
          <div className="format-table fade-in">
            <div className="ft-head">
              <span>Format</span>
              <span>Resolution</span>
              <span>Size</span>
            </div>
            {metadata.formats
              .slice(-8)
              .reverse()
              .map((f) => (
                <div
                  className="ft-row"
                  key={`${f.format_id}-${f.ext}-${f.resolution}`}
                >
                  <span>{f.ext || f.format_id}</span>
                  <span>
                    {f.resolution ||
                      (f.vcodec === "none" ? "audio" : "\u2014")}
                  </span>
                  <span>{fmtBytes(f.filesize)}</span>
                </div>
              ))}
          </div>
        )}
      </section>

      {/* Job Queue */}
      <section className="queue-section">
        <div className="queue-header">
          <span className="header-tag">Queue</span>
          <h2>
            {jobs.length} {jobs.length === 1 ? "Job" : "Jobs"}
          </h2>
        </div>
        <div className="job-list">
          {jobs.map((job) => (
            <article className="job-card" key={job.id}>
              <div className="job-top">
                <span className={`badge ${job.status}`}>{job.status}</span>
                <strong>{job.file_name || job.url}</strong>
              </div>
              <div className="progress-bar">
                <div
                  className="progress-fill"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={Math.round(
                    Math.max(0, Math.min(100, job.progress)),
                  )}
                  style={{
                    width: `${Math.max(0, Math.min(100, job.progress))}%`,
                  }}
                />
              </div>
              <div className="job-bottom">
                <span>{job.message || job.format}</span>
                {job.download_url && (
                  <a
                    className="save-link"
                    href={`${job.download_url}?token=${token}`}
                    download
                  >
                    <Download size={12} /> Save
                  </a>
                )}
              </div>
            </article>
          ))}
          {!jobs.length && (
            <p className="empty-msg">
              No jobs yet \u2014 paste a URL above to start.
            </p>
          )}
        </div>
      </section>
    </main>
  );
}
