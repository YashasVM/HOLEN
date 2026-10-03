import React, { useCallback, useEffect, useMemo, useRef, useState, FormEvent } from "react";
import { UserButton, useAuth } from "@clerk/react";
import {
  CheckSquare,
  Download,
  AlertTriangle,
  Film,
  Gauge,
  Link2,
  List,
  Loader2,
  Music,
  Settings,
  SlidersHorizontal,
  Square,
  Trash2,
  X,
} from "lucide-react";
import { ApiError } from "./types";
import { fmtBytes } from "./lib/format";
import { requestJson } from "./lib/api";
import type { AppUser, Job } from "./types";

/* ── Types ───────────────────────────────────────────────── */

type Metadata = {
  title?: string;
  thumbnail?: string;
  duration?: number;
  uploader?: string;
  webpage_url: string;
  formats: Array<{
    format_id: string; ext?: string; resolution?: string; fps?: number;
    filesize?: number | null; filesize_approx?: number | null;
    vcodec?: string; acodec?: string; height?: number;
  }>;
  options: Array<{ id: string; label: string; description: string; available: boolean; detail: string }>;
};

type PlaylistEntry = { id: string; title: string; url: string; thumbnail?: string; duration?: number };
type PlaylistInfo = { title?: string; uploader?: string; entries: PlaylistEntry[] };

const FORMAT_OPTIONS = [
  { id: "best",  label: "4K / Best MP4" },
  { id: "1080p", label: "1080p MP4" },
  { id: "720p",  label: "720p MP4" },
  { id: "audio", label: "Audio (best)" },
  { id: "mp3",   label: "MP3" },
];

// Backend only allows a few active jobs per user, so cap a single playlist
// batch and leave the rest selected instead of hammering /api/jobs.
const PLAYLIST_QUEUE_CAP = 20;
// The completed-id set only exists to detect "just finished" transitions for
// usage refreshes — cap it (insertion-ordered) and TTL it so it can't grow
// unbounded over a long-lived session.
const COMPLETED_ID_CAP = 500;
const COMPLETED_ID_TTL_MS = 60 * 60 * 1000;
/* ── Helpers ─────────────────────────────────────────────── */

function fmtDuration(s?: number): string {
  if (!s) return "";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  const sec = String(Math.floor(s % 60)).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

// Mirror the backend: yt-dlp reports filesize XOR filesize_approx, and the
// backend normalizes with `filesize or filesize_approx` — do the same here.
function sizeOfFormat(item: { filesize?: number | null; filesize_approx?: number | null }): number {
  return item.filesize ?? item.filesize_approx ?? 0;
}

function estimateBytes(metadata: Metadata, format: string): number | null {
  const sizes = metadata.formats
    .filter((item) => {
      if (format === "audio" || format === "mp3") return item.acodec !== "none" && item.vcodec === "none";
      if (format === "720p") return (item.height || 0) <= 720 && item.vcodec !== "none";
      if (format === "1080p") return (item.height || 0) <= 1080 && item.vcodec !== "none";
      return item.vcodec !== "none";
    })
    .map(sizeOfFormat)
    .filter((n) => n > 0);
  return sizes.length ? Math.max(...sizes) : null;
}

function transferDetails(message?: string | null): string | null {
  if (!message) return null;
  const speed = message.match(/\bat\s+([^\s]+\/s)/i)?.[1];
  const eta = message.match(/ETA\s+([0-9:]+)/i)?.[1];
  if (!speed && !eta) return null;
  return [speed && `${speed}`, eta && `${eta} left`].filter(Boolean).join(" · ");
}

const VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{6,}";

function matchVideoId(value: string): string | null {
  const matchers = [
    new RegExp(`[?&]v=(${VIDEO_ID_PATTERN})`),
    new RegExp(`youtu\\.be/(${VIDEO_ID_PATTERN})`),
    new RegExp(`/shorts/(${VIDEO_ID_PATTERN})`),
    new RegExp(`/live/(${VIDEO_ID_PATTERN})`),
    new RegExp(`/embed/(${VIDEO_ID_PATTERN})`),
    new RegExp(`/v/(${VIDEO_ID_PATTERN})`),
  ];
  for (const re of matchers) {
    const m = value.match(re);
    if (m) return m[1];
  }
  return null;
}

// Handles watch?v=, youtu.be/, /shorts/, /live/, /embed/, /v/. A ?t= timestamp
// param is deliberately ignored — only the video id matters for thumbnails.
function extractVideoId(url: string): string | null {
  try {
    const u = new URL(url);
    const fromQuery = matchVideoId(u.search);
    if (fromQuery) return fromQuery;
    if (u.hostname.toLowerCase().includes("youtu.be")) {
      const short = u.pathname.match(new RegExp(`^/(${VIDEO_ID_PATTERN})`));
      if (short) return short[1];
    }
    const fromPath = matchVideoId(u.pathname);
    if (fromPath) return fromPath;
    return matchVideoId(url);
  } catch {
    return matchVideoId(url);
  }
}

function getJobThumbnail(job: Job): string | null {
  if (job.thumbnail) return job.thumbnail;
  const vid = extractVideoId(job.url);
  if (vid) return `https://i.ytimg.com/vi/${vid}/mqdefault.jpg`;
  return null;
}

function isPlaylistUrl(url: string): boolean {
  const raw = url.trim();
  // Raw-string check first so shapes like youtu.be/…?list= or
  // /shorts/…?list= are caught even if URL parsing normalizes them oddly.
  if (/[?&]list=[\w-]+/.test(raw)) return true;
  try {
    const u = new URL(raw);
    if (u.searchParams.has("list")) return true;
    if (u.pathname.startsWith("/playlist")) return true;
    return false;
  } catch {
    return false;
  }
}

// Human-readable error messages, synced with the backend's `detail` strings
// in prod/backend/app/main.py. Accepts unknown so callers can pass the caught
// error (including ApiError, whose status drives 401/429/507 handling).
function friendlyError(error: unknown): string {
  const status = error instanceof ApiError ? error.status : 0;
  const msg = error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : typeof error === "string"
        ? error
        : "";
  if (!msg) return status ? `Request failed (${status}). Please try again.` : "Something went wrong. Please try again.";
  if (status === 401 || msg.includes("Missing token") || msg.includes("Invalid or expired Clerk session")) {
    return "Session expired — please sign out and sign in again.";
  }
  if (msg.includes("bandwidth allowance")) {
    return "You've used up your bandwidth allowance. Ask an admin to raise it.";
  }
  if (msg.includes("Download authorization")) {
    return "That download authorization expired. Press Save file again for a fresh link.";
  }
  if (msg.includes("File not ready")) {
    return "The file isn't ready yet — wait for the job to finish, then try again.";
  }
  if (msg.includes("link has expired") || msg.includes("expired or missing") || msg.includes("File missing") || msg.includes("File expired")) {
    return "This cached file is unavailable. Paste its link above to prepare it again.";
  }
  if (status === 507 || msg.includes("storage is full")) {
    return "Server storage is full. Ask an admin to clear some files.";
  }
  if (status === 429 || msg.includes("Rate limit")) {
    return "Slow down — too many requests. Try again in a moment.";
  }
  if (msg.includes("Queue is full")) {
    return "The queue is full. Wait for a job to finish, then try again.";
  }
  if (msg.includes("already have")) return msg; // per-user limit message is already friendly
  if (msg.includes("Only YouTube")) return "Only YouTube URLs are supported.";
  if (msg.includes("valid http")) return "Please enter a valid URL (https://youtube.com/...).";
  if (msg.includes("minutes or shorter")) return msg;
  if (msg.includes("Metadata lookup failed") || msg.includes("Playlist lookup failed") || msg.includes("Preview failed") || msg.includes("Not a playlist")) {
    return "Couldn't fetch video info. Check the URL or try again.";
  }
  if (msg.includes("Could not determine download size") || msg.includes("did not provide a download size")) return msg;
  if (msg.includes("Server restarted during this job")) return msg;
  if (msg.includes("Auto-deleted")) return msg;
  return msg;
}

// Minimal p-limit (concurrency gate) so playlist batches don't hammer the
// backend. Inline on purpose: adding the `p-limit` package would require
// touching package.json, which is out of scope.
function pLimit(concurrency: number) {
  const queue: Array<() => void> = [];
  let active = 0;
  const pump = () => {
    while (active < concurrency && queue.length > 0) {
      const task = queue.shift();
      if (!task) break;
      active += 1;
      task();
    }
  };
  return <T,>(fn: () => Promise<T>): Promise<T> =>
    new Promise<T>((resolve, reject) => {
      queue.push(() => {
        fn().then(
          (value) => {
            active -= 1;
            pump();
            resolve(value);
          },
          (err) => {
            active -= 1;
            pump();
            reject(err);
          },
        );
      });
      pump();
    });
}

// Shallow job equality for queue refreshes: unchanged jobs keep their object
// identity so memoized cards skip re-render (avoids full-list rerender and
// the scroll jump that comes with it).
function sameJob(a: Job, b: Job): boolean {
  return a.id === b.id
    && a.status === b.status
    && a.progress === b.progress
    && a.format === b.format
    && a.url === b.url
    && a.created_at === b.created_at
    && (a.message ?? "") === (b.message ?? "")
    && (a.title ?? "") === (b.title ?? "")
    && (a.thumbnail ?? "") === (b.thumbnail ?? "")
    && (a.download_url ?? "") === (b.download_url ?? "")
    && (a.file_name ?? "") === (b.file_name ?? "")
    && (a.expires_at ?? "") === (b.expires_at ?? "");
}

function mergeJobs(prev: Job[], next: Job[]): Job[] {
  if (prev.length === next.length && prev.every((job, index) => sameJob(job, next[index]))) return prev;
  const prevById = new Map(prev.map((job) => [job.id, job] as const));
  return next.map((job) => {
    const old = prevById.get(job.id);
    return old && sameJob(old, job) ? old : job;
  });
}

/* ── Props ───────────────────────────────────────────────── */

interface DownloaderPageProps {
  user: AppUser;
  onAdminClick: () => void;
}

/* ── Toast ───────────────────────────────────────────────── */

type Toast = { id: number; msg: string; ok: boolean; action?: { label: string; onClick: () => void } };
let _toastId = 0;

function useToast() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const show = useCallback((msg: string, ok = true, action?: Toast["action"]) => {
    const id = ++_toastId;
    setToasts((t) => [...t, { id, msg, ok, action }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500);
  }, []);
  return { toasts, show };
}

const FormatPicker = React.memo(function FormatPicker({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [advanced, setAdvanced] = useState(false);
  const primary = FORMAT_OPTIONS.filter((option) => option.id === "best" || option.id === "audio");
  const secondary = FORMAT_OPTIONS.filter((option) => option.id !== "best" && option.id !== "audio");
  const options = advanced ? [...primary, ...secondary] : primary;
  return (
    <div className="format-picker" aria-label="Download format">
      <div className="format-picker-options">
        {options.map((option) => (
          <button className={`format-choice ${value === option.id ? "is-active" : ""}`} type="button" key={option.id} onClick={() => onChange(option.id)} aria-pressed={value === option.id}>
            {option.label}
          </button>
        ))}
      </div>
      <button className="format-advanced" type="button" onClick={() => setAdvanced((current) => !current)} aria-expanded={advanced}>
        <SlidersHorizontal size={14} /> {advanced ? "Less" : "More formats"}
      </button>
    </div>
  );
});

/* ── JobCard (memoized: unchanged jobs skip re-render) ────── */

type JobCardProps = {
  job: Job;
  selected: boolean;
  selectable: boolean;
  queuePosition?: number;
  downloading: boolean;
  onToggleSelect: (jobId: string) => void;
  onCancel: (jobId: string) => void;
  onDownload: (job: Job) => void;
};

const JobCard = React.memo(function JobCard({ job, selected, selectable, queuePosition, downloading, onToggleSelect, onCancel, onDownload }: JobCardProps) {
  const thumb = getJobThumbnail(job);
  const canCancel = job.status === "queued" || job.status === "running";
  const isAudio = job.format === "audio" || job.format === "mp3";
  const transfer = transferDetails(job.message);
  const jobDetail = job.status === "failed"
    ? `✗ ${job.message || "Failed"}`
    : job.status === "queued"
      ? `Queue position ${queuePosition ?? 1}`
      : job.status === "running"
        ? `${Math.round(Math.max(0, Math.min(100, job.progress)))}% · ${transfer ? "Downloading source" : job.message || "Preparing file"}`
        : null;
  return (
    <article className={`job-card${selected ? " job-card-selected" : ""}`}>
      <div className="job-card-inner">
        {selectable && (
          <button className="job-check" type="button" onClick={() => onToggleSelect(job.id)} aria-label="Select job" aria-pressed={selected}>
            {selected ? <CheckSquare size={16} /> : <Square size={16} />}
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
              <button className="btn btn-dark" type="button" title="Cancel" aria-label="Cancel download" onClick={() => onCancel(job.id)} style={{ marginLeft: "auto", padding: "2px 6px", minWidth: 0 }}>
                <X size={12} />
              </button>
            )}
          </div>
          {(job.status === "running" || job.status === "queued") && (
            <div className="progress-bar">
              <div
                className="progress-fill"
                role="progressbar"
                aria-label="Preparing download"
                aria-valuemin={0} aria-valuemax={100}
                aria-valuenow={Math.round(Math.max(0, Math.min(100, job.progress)))}
                style={{ transform: `scaleX(${Math.max(0, Math.min(100, job.progress)) / 100})` }}
              />
            </div>
          )}
          {(jobDetail || (job.status === "running" && transfer)) && (
            <div className="job-bottom">
              {jobDetail && <span className="job-msg">{jobDetail}</span>}
              {job.status === "running" && transfer && <span className="job-transfer"><Gauge size={13} /> {transfer}</span>}
            </div>
          )}
        </div>
      </div>
      {job.download_url && (
        <>
          <div className="job-download-row">
            <button className="download-btn" type="button" disabled={downloading} onClick={() => onDownload(job)}>
              {downloading ? <Loader2 className="spin" size={16} /> : <Download size={16} />}<span>{downloading ? "Authorizing…" : "Save file"}</span>
            </button>
          </div>
          <p className="link-expiry">Ready to save to your device</p>
        </>
      )}
    </article>
  );
});

/* ── Component ───────────────────────────────────────────── */

export function DownloaderPage({ user, onAdminClick }: DownloaderPageProps) {
  const { getToken } = useAuth();
  const [url, setUrl] = useState("");
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [playlist, setPlaylist] = useState<PlaylistInfo | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectedFormat, setSelectedFormat] = useState("best");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [busy, setBusy] = useState(false);
  const [plQueueing, setPlQueueing] = useState(false);
  const [plProgress, setPlProgress] = useState<{ done: number; total: number } | null>(null);
  const [error, setError] = useState("");
  const [selectedJobs, setSelectedJobs] = useState<Set<string>>(new Set());
  const [usage, setUsage] = useState(user);
  const [queueError, setQueueError] = useState("");
  const [downloadingIds, setDownloadingIds] = useState<Set<string>>(new Set());
  const downloadLocks = useRef(new Set<string>());
  const { toasts, show: showToast } = useToast();
  // Map of completed job id -> first-seen timestamp. Capped + TTL-pruned so a
  // long-lived session can't grow it without bound.
  const completedJobIdsRef = useRef<Map<string, number>>(new Map());
  // Ids hidden by an optimistic clear that the server hasn't confirmed yet.
  // Queue responses are filtered against this set so cleared jobs don't pop back
  // in (server-confirmed deletes only).
  const pendingClearIdsRef = useRef<Set<string>>(new Set());
  const pendingClearRef = useRef<{ timer: number; restore: Job[] } | null>(null);
  // Guards the one-shot focus listener that refreshes usage after a download.
  const usageRefreshArmedRef = useRef(false);

  const apiFetch = useCallback(<T,>(path: string, init?: RequestInit) => requestJson<T>(path, getToken, init), [getToken]);

  const refreshUsage = useCallback(async () => {
    try {
      setUsage(await apiFetch<AppUser>("/api/me"));
    } catch {
      // The next regular profile refresh will retry if this transient request fails.
    }
  }, [apiFetch]);

  const rememberCompletedIds = useCallback((ids: string[]) => {
    if (ids.length === 0) return;
    const store = completedJobIdsRef.current;
    const now = Date.now();
    for (const id of ids) store.set(id, now);
    if (store.size > COMPLETED_ID_CAP) {
      const overflow = store.size - COMPLETED_ID_CAP;
      const keys = store.keys();
      for (let i = 0; i < overflow; i += 1) {
        const oldest = keys.next().value;
        if (oldest === undefined) break;
        store.delete(oldest);
      }
    }
    // Amortized TTL sweep once the set is half full.
    if (store.size > COMPLETED_ID_CAP / 2) {
      const cutoff = now - COMPLETED_ID_TTL_MS;
      for (const [id, seenAt] of store) {
        if (seenAt < cutoff) store.delete(id);
      }
    }
  }, []);

  // Merge a server job list into state: unchanged jobs keep their identity
  // (memoized cards skip re-render, no scroll jump), locally-cleared ids stay
  // hidden until the server confirms the delete, and fresh completions refresh
  // the bandwidth readout.
  const applyJobsList = useCallback((incoming: Job[]) => {
    const hidden = pendingClearIdsRef.current;
    const visible = hidden.size > 0 ? incoming.filter((job) => !hidden.has(job.id)) : incoming;
    setJobs((prev) => mergeJobs(prev, visible));
    const completedIds = visible.filter((job) => job.status === "completed").map((job) => job.id);
    const fresh = completedIds.filter((id) => !completedJobIdsRef.current.has(id));
    if (completedIds.length > 0) rememberCompletedIds(completedIds);
    if (fresh.length > 0) void refreshUsage();
  }, [refreshUsage, rememberCompletedIds]);

  useEffect(() => { setUsage(user); }, [user]);

  useEffect(() => {
    if (user.is_restricted_email && user.quota_notice) showToast(user.quota_notice, false);
  }, [showToast, user.is_restricted_email, user.quota_notice]);

  async function analyze(event?: FormEvent) {
    event?.preventDefault();
    const trimmed = url.trim();
    if (!trimmed) return;
    setBusy(true);
    setError("");
    setMetadata(null);
    setPlaylist(null);
    setSelectedIds(new Set());
    const fetchPlaylist = async () => {
      const info = await apiFetch<PlaylistInfo>("/api/playlist", { method: "POST", body: JSON.stringify({ url: trimmed }) });
      setPlaylist(info);
      setSelectedIds(new Set(info.entries.map((entry) => entry.id)));
    };
    try {
      if (isPlaylistUrl(trimmed)) {
        try {
          await fetchPlaylist();
          return;
        } catch (err) {
          // isPlaylistUrl can misfire — if the backend says it isn't a
          // playlist, fall through to single-video analyze.
          if (!(err instanceof ApiError) || !/not a playlist/i.test(err.detail)) throw err;
        }
      }
      try {
        setMetadata(await apiFetch<Metadata>("/api/analyze", { method: "POST", body: JSON.stringify({ url: trimmed }) }));
      } catch (err) {
        // isPlaylistUrl misses some playlist shapes (youtu.be?list=,
        // /shorts/?list=) — retry those as a playlist before giving up.
        if (trimmed.includes("list=")) {
          await fetchPlaylist();
          return;
        }
        throw err;
      }
    } catch (err) {
      setError(friendlyError(err));
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

  async function handleDownload(event?: FormEvent) {
    event?.preventDefault();
    if (busy || !url.trim()) return;
    if (isPlaylistUrl(url)) { await analyze(); return; }
    setBusy(true);
    setError("");
    try {
      const job = await createJob(metadata?.webpage_url || url.trim(), selectedFormat, metadata?.title, metadata?.thumbnail);
      setJobs((cur) => (cur.some((j) => j.id === job.id) ? cur : [job, ...cur]));
      showToast("Added to queue. Save the file when it is ready.");
      setMetadata(null);
      setUrl("");
    } catch (err) {
      setError(friendlyError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handlePlaylistDownload() {
    if (!playlist || plQueueing) return;
    const selected = playlist.entries.filter((entry) => selectedIds.has(entry.id));
    if (selected.length === 0) return;
    // Cap the batch; leftovers stay selected so the user can queue them next.
    const ingest = selected.slice(0, PLAYLIST_QUEUE_CAP);
    // Dedicated queueing state (not the global `busy`) so the rest of the UI
    // stays interactive, with live progress for the batch.
    setPlQueueing(true);
    setPlProgress({ done: 0, total: ingest.length });
    setError("");
    const limit = pLimit(2);
    let unauthorized = false;
    const queuedIds = new Set<string>();
    const results = await Promise.all(
      ingest.map((entry) =>
        limit(async () => {
          if (unauthorized) return false;
          try {
            const job = await createJob(entry.url, selectedFormat, entry.title, entry.thumbnail);
            queuedIds.add(entry.id);
            setJobs((current) => (current.some((item) => item.id === job.id) ? current : [job, ...current]));
            return true;
          } catch (err) {
            // Per-entry failure: record it and continue. A 401 means the
            // session died — stop the batch, the rest would fail too.
            if (err instanceof ApiError && err.status === 401) unauthorized = true;
            return false;
          } finally {
            setPlProgress((progress) => (progress ? { ...progress, done: Math.min(progress.total, progress.done + 1) } : progress));
          }
        }),
      ),
    );
    const failed = results.filter((ok) => !ok).length;
    const queuedCount = ingest.length - failed;
    setPlQueueing(false);
    setPlProgress(null);
    if (unauthorized) {
      setError(friendlyError(new ApiError(401, "Missing token")));
    } else if (queuedCount === 0) {
      setError(`${ingest.length} video(s) failed to queue. Check limits and try again.`);
    } else if (failed > 0) {
      setError(`${failed} of ${ingest.length} video(s) failed to queue. The rest were queued.`);
    } else if (selected.length > PLAYLIST_QUEUE_CAP) {
      showToast(`Queued ${queuedCount} video(s) — ${selected.length - PLAYLIST_QUEUE_CAP} left over the ${PLAYLIST_QUEUE_CAP}-video cap`);
    } else {
      showToast(`Queued ${queuedCount} video(s)`);
    }
    if (queuedCount > 0 && failed === 0 && selected.length <= PLAYLIST_QUEUE_CAP) {
      setPlaylist(null);
      setUrl("");
      setSelectedIds(new Set());
    } else {
      // Keep the picker open with only the not-yet-queued entries selected.
      setSelectedIds(new Set(selected.filter((entry) => !queuedIds.has(entry.id)).map((entry) => entry.id)));
    }
  }

  const toggleJobSelection = useCallback((jobId: string) => {
    setSelectedJobs((prev) => {
      const next = new Set(prev);
      if (next.has(jobId)) next.delete(jobId);
      else next.add(jobId);
      return next;
    });
  }, []);

  const handleCancelJob = useCallback(async (jobId: string) => {
    setJobs((cur) => cur.map((j) => j.id === jobId ? { ...j, status: "cancelled" as const } : j));
    try {
      await apiFetch(`/api/jobs/${jobId}`, { method: "DELETE" });
      showToast("Job cancelled");
    } catch (err) {
      showToast(friendlyError(err), false);
      try { applyJobsList(await apiFetch<Job[]>("/api/jobs")); } catch { /* next refresh retries */ }
    }
  }, [apiFetch, applyJobsList, showToast]);

  function undoClear() {
    const pending = pendingClearRef.current;
    if (!pending) return;
    window.clearTimeout(pending.timer);
    for (const job of pending.restore) pendingClearIdsRef.current.delete(job.id);
    setJobs((current) => [...current, ...pending.restore.filter((job) => !current.some((item) => item.id === job.id))].sort((a, b) => b.created_at.localeCompare(a.created_at)));
    pendingClearRef.current = null;
    showToast("Clear undone");
  }

  function stageClear(ids: string[]) {
    const restore = jobs.filter((job) => ids.includes(job.id) && job.status !== "running" && job.status !== "queued");
    if (!restore.length) return;
    if (pendingClearRef.current) undoClear();
    const restoreIds = restore.map((job) => job.id);
    for (const id of restoreIds) pendingClearIdsRef.current.add(id);
    setJobs((current) => current.filter((job) => !ids.includes(job.id) || job.status === "running" || job.status === "queued"));
    setSelectedJobs(new Set());
    const timer = window.setTimeout(() => {
      void apiFetch<{ deleted: number }>("/api/jobs", { method: "DELETE", body: JSON.stringify({ ids: restoreIds }) })
        .then((result) => showToast(`Cleared ${result.deleted} job(s)`))
        .catch(() => {
          for (const id of restoreIds) pendingClearIdsRef.current.delete(id);
          setJobs((current) => [...current, ...restore.filter((job) => !current.some((item) => item.id === job.id))].sort((a, b) => b.created_at.localeCompare(a.created_at)));
        })
        .finally(() => {
          for (const id of restoreIds) pendingClearIdsRef.current.delete(id);
          if (pendingClearRef.current?.timer === timer) pendingClearRef.current = null;
        });
    }, 5000);
    pendingClearRef.current = { timer, restore };
    showToast(`${restore.length} job(s) will be cleared`, true, { label: "Undo", onClick: undoClear });
  }

  function deleteSelectedJobs() { stageClear([...selectedJobs]); }

  function clearAllCompleted() { stageClear(jobs.filter((job) => job.status !== "running" && job.status !== "queued").map((job) => job.id)); }

  const handleDownloadFile = useCallback(async (job: Job) => {
    if (!job.download_url || downloadLocks.current.has(job.id)) return;
    downloadLocks.current.add(job.id);
    setDownloadingIds(new Set(downloadLocks.current));
    try {
      await apiFetch(`/api/jobs/${job.id}/download-ticket`, { method: "POST" });
      const anchor = document.createElement("a");
      anchor.href = job.download_url;
      anchor.download = job.file_name || "download";
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      showToast("Sent to your browser downloads. Check its download list.");
      // Egress is charged when the bytes actually flow, so refresh now and
      // once more when the tab regains focus (no arbitrary timeout).
      void refreshUsage();
      if (!usageRefreshArmedRef.current) {
        usageRefreshArmedRef.current = true;
        const onReturn = () => {
          usageRefreshArmedRef.current = false;
          void refreshUsage();
        };
        window.addEventListener("focus", onReturn, { once: true });
      }
    } catch (err) {
      showToast(friendlyError(err), false);
    } finally {
      downloadLocks.current.delete(job.id);
      setDownloadingIds(new Set(downloadLocks.current));
    }
  }, [apiFetch, refreshUsage, showToast]);

  const hasActiveJobs = jobs.some((job) => job.status === "queued" || job.status === "running");
  useEffect(() => {
    let stopped = false;
    let inflight = false;
    let timer: number | undefined;
    const controller = new AbortController();
    const refresh = async () => {
      if (stopped || inflight || document.hidden) return;
      inflight = true;
      window.clearTimeout(timer);
      try {
        const list = await apiFetch<Job[]>("/api/jobs", { signal: controller.signal });
        if (!stopped) { applyJobsList(list); setQueueError(""); }
      } catch (err) {
        if (!stopped) setQueueError(friendlyError(err));
      } finally {
        inflight = false;
        if (!stopped && hasActiveJobs && !document.hidden) timer = window.setTimeout(() => void refresh(), 3000);
      }
    };
    const onVisible = () => { if (!document.hidden) void refresh(); };
    void refresh();
    window.addEventListener("focus", onVisible);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stopped = true;
      controller.abort();
      window.clearTimeout(timer);
      window.removeEventListener("focus", onVisible);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [apiFetch, applyJobsList, hasActiveJobs]);

  useEffect(() => () => {
    if (pendingClearRef.current) window.clearTimeout(pendingClearRef.current.timer);
  }, []);

  function toggleEntry(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!playlist) return;
    const allIds = new Set(playlist.entries.map((entry) => entry.id));
    setSelectedIds(allIds.size === selectedIds.size ? new Set() : allIds);
  }

  /* ── Derived state (memoized: new Map/Set identity each render would
        otherwise re-render every consumer) ─────────────────────── */
  const activeJobs = useMemo(() => jobs.filter((j) => j.status === "running" || j.status === "queued"), [jobs]);
  const doneJobs = useMemo(() => jobs.filter((j) => j.status !== "running" && j.status !== "queued"), [jobs]);
  const queuedPositions = useMemo(() => new Map<string, number>(
    jobs
      .filter((job) => job.status === "queued")
      .sort((a, b) => a.created_at.localeCompare(b.created_at))
      .map((job, index): [string, number] => [job.id, index + 1]),
  ), [jobs]);
  const allIds = useMemo(
    () => new Set<string>(playlist ? playlist.entries.map((entry) => entry.id) : []),
    [playlist],
  );
  const allSelected = useMemo(
    () => (playlist ? allIds.size === selectedIds.size : false),
    [allIds, playlist, selectedIds],
  );
  const clearableCount = doneJobs.length;
  const usagePercent = useMemo(
    () => Math.min(100, Math.round(((usage?.used_bytes ?? 0) / Math.max(1, usage?.usage_limit_bytes ?? 1)) * 100)),
    [usage],
  );
  const estimatedBytes = metadata ? estimateBytes(metadata, selectedFormat) : null;

  /* ── Main Downloader UI ─────────────────────────────────── */

  return (
    <main className="app-shell fade-in">
      {/* Toasts */}
      <div className="toast-container" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.ok ? "toast-ok" : "toast-err"} slide-up`}>
            <span>{t.msg}</span>
            {t.action && <button type="button" onClick={t.action.onClick}>{t.action.label}</button>}
          </div>
        ))}
      </div>

      {/* Header */}
      <header className="header">
        <div className="header-left">
          <div className="header-badge">H</div>
          <div className="header-text">
            <span className="header-tag">Private node</span>
            <h1>Holen</h1>
          </div>
          {activeJobs.length > 0 && (
            <span className="job-count-badge" title={`${activeJobs.length} active job(s)`}>
              {activeJobs.length}
            </span>
          )}
        </div>
        <div className="header-actions">
          <div className="usage-chip" title="Downloads into the server and downloads to your device both count">
            <span className="usage-label">Bandwidth</span>
            <strong className="usage-value">{fmtBytes(usage?.used_bytes ?? 0)} / {fmtBytes(usage?.usage_limit_bytes ?? 0)}</strong>
            <div className="usage-meter" aria-label={`${usagePercent}% of bandwidth used`}>
              <i style={{ "--usage-progress": usagePercent / 100 } as React.CSSProperties} />
            </div>
            <small className="usage-detail">{fmtBytes(usage?.remaining_bytes ?? 0)} remaining · {usagePercent}% used</small>
          </div>
          {user.is_admin && (
            <button className="btn btn-outline" type="button" onClick={onAdminClick}>
              <Settings size={14} /> Admin
            </button>
          )}
          <UserButton appearance={{ elements: { avatarBox: "clerk-avatar", avatarImage: "clerk-avatar-image" } }} />
        </div>
      </header>

      {usage?.is_restricted_email && usage?.quota_notice && (
        <aside className="email-quota-notice" role="status">
          <AlertTriangle size={18} aria-hidden="true" />
          <p>{usage.quota_notice}</p>
        </aside>
      )}

      {/* URL Input */}
      <section className="url-card">
        <form className="url-form" onSubmit={handleDownload}>
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
          <button className="btn btn-primary" type="submit" disabled={busy || !url.trim()}>
            {busy ? <Loader2 className="spin" size={16} /> : <Download size={16} />} {isPlaylistUrl(url) ? "Choose videos" : "Download"}
          </button>
        </form>
        <div className="source-options">
          <FormatPicker value={selectedFormat} onChange={setSelectedFormat} />
          <button className="btn btn-outline" type="button" disabled={busy || !url.trim()} onClick={() => void analyze()}>Preview details</button>
        </div>
        <p className="source-help">Paste a link, choose a format, and download. Your file appears below when it is ready.</p>

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
              <div className="download-preflight">
                <FormatPicker value={selectedFormat} onChange={setSelectedFormat} />
                {estimatedBytes ? <p className="transfer-estimate">Est. file {fmtBytes(estimatedBytes)} · up to {fmtBytes(estimatedBytes * 2)} total transfer</p> : <p className="transfer-estimate">File size unavailable · transfer use is charged after download</p>}
              </div>
              <div className="format-row">
                <button className="btn btn-primary mobile-download-action" type="button" onClick={handleDownload} disabled={busy}>
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
                <FormatPicker value={selectedFormat} onChange={setSelectedFormat} />
                <button className="btn btn-primary" type="button" onClick={handlePlaylistDownload} disabled={plQueueing || selectedIds.size === 0}>
                  {plQueueing ? <Loader2 className="spin" size={14} /> : <Download size={14} />}
                  {plQueueing && plProgress
                    ? `Queueing ${plProgress.done}/${plProgress.total}…`
                    : selectedIds.size > 0 ? `Queue ${selectedIds.size}` : "Queue"}
                </button>
              </div>
              {plQueueing && plProgress && (
                <p className="transfer-estimate" role="status" aria-live="polite">
                  Queueing {plProgress.done} of {plProgress.total} (2 at a time)…
                </p>
              )}
              {selectedIds.size > PLAYLIST_QUEUE_CAP && !plQueueing && (
                <p className="transfer-estimate" role="status">
                  Only the first {PLAYLIST_QUEUE_CAP} will be queued — the rest stay selected.
                </p>
              )}
            </div>
            <button className="playlist-select-all" type="button" onClick={toggleAll}>
              {allSelected ? <CheckSquare size={15} /> : <Square size={15} />}
              {allSelected ? "Deselect all" : "Select all"}
            </button>
            <div className="playlist-entries">
              {playlist.entries.map((entry) => {
                const checked = selectedIds.has(entry.id);
                return (
                  <button type="button" aria-pressed={checked} className={`playlist-entry ${checked ? "selected" : ""}`} key={entry.id} onClick={() => toggleEntry(entry.id)}>
                    <span className="entry-check">{checked ? <CheckSquare size={16} /> : <Square size={16} />}</span>
                    {entry.thumbnail
                      ? <img className="entry-thumb" src={entry.thumbnail} alt="" loading="lazy" />
                      : <div className="entry-thumb-placeholder"><Film size={14} /></div>}
                    <span className="entry-title">{entry.title}</span>
                    {entry.duration ? <span className="entry-dur muted">{fmtDuration(entry.duration)}</span> : null}
                  </button>
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

        {queueError && <p className="error-msg" role="alert">Queue could not refresh: {queueError} <button type="button" className="btn btn-outline" onClick={() => void apiFetch<Job[]>("/api/jobs").then((list) => { applyJobsList(list); setQueueError(""); }).catch((err) => setQueueError(friendlyError(err)))}>Retry</button></p>}
        <div className="job-list">
          {jobs.map((job) => (
            <JobCard
              key={job.id}
              job={job}
              selected={selectedJobs.has(job.id)}
              selectable={job.status !== "queued" && job.status !== "running"}
              queuePosition={queuedPositions.get(job.id)}
              downloading={downloadingIds.has(job.id)}
              onToggleSelect={toggleJobSelection}
              onCancel={handleCancelJob}
              onDownload={handleDownloadFile}
            />
          ))}
          {!jobs.length && (
            <div className="empty-state">
              <Download size={32} className="empty-icon" />
              <p>No jobs yet. Paste a YouTube video or playlist URL to start.</p>
              <code>youtube.com/watch?v=…</code>
              <small>Video up to 4K, audio, and MP3 are supported.</small>
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
