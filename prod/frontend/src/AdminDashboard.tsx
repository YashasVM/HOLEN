import { UserButton, useClerk, useAuth } from "@clerk/react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowLeft,
  BarChart3,
  CheckSquare,
  Database,
  Download,
  HardDrive,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Square,
  Trash2,
  Users,
} from "lucide-react";
import type { AppUser } from "./types";
import { fmtBytes } from "./lib/format";
import { requestJson } from "./lib/api";

type CachedFile = {
  id: string;
  title?: string;
  file_name?: string;
  format: string;
  created_at: string;
  expires_at?: string;
  size_bytes: number;
};

type Notice = { text: string; kind: "success" | "error" };

type AdminTelemetry = {
  active_jobs: Array<{
    id: string; title?: string; format: string; status: "queued" | "running";
    progress: number; message?: string; user_email?: string; created_at: string;
  }>;
  activity: Array<{ date: string; label: string; downloads: number; completed: number; failed: number }>;
  bandwidth: { ingress_bytes: number; egress_bytes: number; total_bytes: number };
  cache: { used_bytes: number; limit_bytes: number; percent_used: number };
  generated_at: string;
};

interface AdminDashboardProps {
  user: AppUser;
  onBack: () => void;
}

const LIMIT_PRESETS_GB = [5, 10, 25, 50, 100, 250];
const GB = 1024 ** 3;

function usagePercent(user: AppUser): number {
  return Math.min(100, Math.round((user.used_bytes / Math.max(1, user.usage_limit_bytes)) * 100));
}

/** Quota <select> options: presets plus the user's current value so a
 *  non-preset limit (e.g. a 2 GB restricted quota) still matches an
 *  <option> instead of rendering blank + a React warning. */
function limitOptions(currentBytes: number): number[] {
  const presets = LIMIT_PRESETS_GB.map((gb) => gb * GB);
  if (presets.includes(currentBytes)) return presets;
  return [...presets, currentBytes].sort((a, b) => a - b);
}

function formatLimitOption(bytes: number): string {
  if (bytes % GB === 0) return `${bytes / GB} GB`;
  return `${fmtBytes(bytes)} (current)`;
}

export function AdminDashboard({ user, onBack }: AdminDashboardProps) {
  const { getToken } = useAuth();
  const { signOut } = useClerk();
  const [files, setFiles] = useState<CachedFile[]>([]);
  const [users, setUsers] = useState<AppUser[]>([]);
  const [telemetry, setTelemetry] = useState<AdminTelemetry | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  // Per-operation busy keys (e.g. `delete-<fileId>`, `user-<userId>`) so
  // independent rows can run in parallel; only the same row is blocked.
  const [busyKeys, setBusyKeys] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  // Inline (non-blocking) delete confirmation; null = no pending confirm.
  const [pendingDelete, setPendingDelete] = useState<string[] | null>(null);

  const isBusy = useCallback((key: string) => busyKeys.has(key), [busyKeys]);

  const setKeysBusy = useCallback((keys: string[], busy: boolean) => {
    setBusyKeys((current) => {
      const next = new Set(current);
      for (const key of keys) {
        if (busy) next.add(key);
        else next.delete(key);
      }
      return next;
    });
  }, []);

  const request = useCallback(<T,>(path: string, init?: RequestInit) => requestJson<T>(path, getToken, init), [getToken]);

  const reload = useCallback(async () => {
    // Skip background refreshes; visibilitychange/focus handlers below
    // trigger a reload when the tab becomes visible again.
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    setLoading(true);
    try {
      // allSettled: one failing endpoint must not wipe the slices that
      // did succeed (partial UI instead of a blank dashboard).
      const [filesResult, usersResult, telemetryResult] = await Promise.allSettled([
        request<CachedFile[]>("/api/admin/files"),
        request<AppUser[]>("/api/admin/users"),
        request<AdminTelemetry>("/api/admin/telemetry"),
      ]);
      const failed: string[] = [];
      if (filesResult.status === "fulfilled") {
        setFiles(filesResult.value);
        setSelected((current) => new Set([...current].filter((id) => filesResult.value.some((file) => file.id === id))));
      } else {
        failed.push("cached files");
      }
      if (usersResult.status === "fulfilled") {
        setUsers(usersResult.value);
      } else {
        failed.push("users");
      }
      if (telemetryResult.status === "fulfilled") {
        setTelemetry(telemetryResult.value);
      } else {
        failed.push("telemetry");
      }
      if (failed.length === 3) {
        const firstError = filesResult.status === "rejected" ? filesResult.reason : null;
        setNotice({
          text: firstError instanceof Error ? firstError.message : "Could not load the dashboard",
          kind: "error",
        });
      } else if (failed.length > 0) {
        setNotice({ text: `Partially loaded: could not refresh ${failed.join(", ")}.`, kind: "error" });
      }
    } catch (error) {
      setNotice({ text: error instanceof Error ? error.message : "Could not load the dashboard", kind: "error" });
    } finally {
      setLoading(false);
    }
  }, [request]);

  useEffect(() => { void reload(); }, [reload]);
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") void reload();
    };
    const onFocus = () => void reload();
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onFocus);
    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onFocus);
    };
  }, [reload]);

  const totalCache = useMemo(() => files.reduce((sum, file) => sum + file.size_bytes, 0), [files]);
  const selectedSize = useMemo(
    () => files.reduce((sum, file) => sum + (selected.has(file.id) ? file.size_bytes : 0), 0),
    [files, selected],
  );
  const allSelected = files.length > 0 && selected.size === files.length;
  const activityMax = useMemo(
    () => Math.max(1, ...(telemetry?.activity.map((point) => point.downloads) || [])),
    [telemetry],
  );
  const activityTotal = useMemo(
    () => telemetry?.activity.reduce((sum, point) => sum + point.downloads, 0) || 0,
    [telemetry],
  );
  // Any in-flight delete blocks only delete buttons, never quota updates.
  const anyDeleteBusy = useMemo(
    () => [...busyKeys].some((key) => key.startsWith("delete-")),
    [busyKeys],
  );

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  function requestDelete(ids: string[]) {
    if (!ids.length) return;
    setPendingDelete(ids);
  }

  async function confirmDelete() {
    const ids = pendingDelete;
    if (!ids?.length) return;
    const keys = ids.map((id) => `delete-${id}`);
    setKeysBusy(keys, true);
    try {
      const result = await request<{ deleted: number; freed_bytes: number }>("/api/admin/files", {
        method: "DELETE",
        body: JSON.stringify(ids),
      });
      setNotice({ text: `Deleted ${result.deleted} file(s) and freed ${fmtBytes(result.freed_bytes)}.`, kind: "success" });
      setSelected(new Set());
      setPendingDelete(null);
      await reload();
    } catch (error) {
      setNotice({ text: error instanceof Error ? error.message : "Delete failed", kind: "error" });
    } finally {
      setKeysBusy(keys, false);
    }
  }

  async function updateUser(target: AppUser, patch: { usage_limit_bytes?: number }) {
    const key = `user-${target.id}`;
    if (isBusy(key)) return;
    setKeysBusy([key], true);
    try {
      const updated = await request<AppUser>(`/api/admin/users/${encodeURIComponent(target.id)}`, {
        method: "PATCH",
        body: JSON.stringify(patch),
      });
      setUsers((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice({ text: `${updated.name || updated.email || "User"} was updated.`, kind: "success" });
    } catch (error) {
      setNotice({ text: error instanceof Error ? error.message : "Update failed", kind: "error" });
    } finally {
      setKeysBusy([key], false);
    }
  }

  return (
    <main className="app-shell admin-shell fade-in">
      <header className="header">
        <div className="header-left">
          <button className="btn btn-outline" type="button" onClick={onBack}><ArrowLeft size={16} /> Back</button>
          <div className="header-text">
            <span className="header-tag header-tag-red">Owner controls</span>
            <h1>Admin desk</h1>
          </div>
        </div>
        <div className="header-actions">
          <button className="icon-button" type="button" onClick={() => void reload()} aria-label="Refresh dashboard" disabled={loading}>
            <RefreshCw className={loading ? "spin" : ""} size={18} />
          </button>
          <UserButton appearance={{ elements: { avatarBox: "clerk-avatar" } }} />
          <button className="btn btn-dark" type="button" onClick={() => void signOut()}>Sign out</button>
        </div>
      </header>

      {notice && <div className={`notice notice-${notice.kind}`} role="status"><span>{notice.text}</span><button onClick={() => setNotice(null)} aria-label="Dismiss">×</button></div>}

      {pendingDelete && pendingDelete.length > 0 && (
        <div className="notice notice-error" role="alertdialog" aria-label="Confirm delete">
          <span>Delete {pendingDelete.length} cached file{pendingDelete.length === 1 ? "" : "s"}? This cannot be undone.</span>
          <span style={{ display: "inline-flex", gap: "0.5rem", marginLeft: "0.75rem" }}>
            <button
              className="btn btn-delete"
              type="button"
              onClick={() => void confirmDelete()}
              disabled={pendingDelete.some((id) => isBusy(`delete-${id}`))}
            >
              {pendingDelete.some((id) => isBusy(`delete-${id}`)) ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}
              Confirm delete
            </button>
            <button className="btn btn-outline" type="button" onClick={() => setPendingDelete(null)}>Cancel</button>
          </span>
        </div>
      )}

      <section className="admin-summary" aria-label="Dashboard summary">
        <article><Database size={22} /><span>Cache</span><strong>{fmtBytes(telemetry?.cache.used_bytes ?? totalCache)}</strong><small>{telemetry ? `${telemetry.cache.percent_used}% of ${fmtBytes(telemetry.cache.limit_bytes)}` : `${files.length} file(s)`}</small></article>
        <article><Activity size={22} /><span>Bandwidth</span><strong>{fmtBytes(telemetry?.bandwidth.total_bytes ?? 0)}</strong><small>In {fmtBytes(telemetry?.bandwidth.ingress_bytes ?? 0)} · Out {fmtBytes(telemetry?.bandwidth.egress_bytes ?? 0)}</small></article>
        <article><Users size={22} /><span>Accounts</span><strong>{users.length}</strong><small>{users.filter((item) => item.is_admin).length} admin(s)</small></article>
        <article><ShieldCheck size={22} /><span>Owner</span><strong>@{user.github_username || "YashasVM"}</strong><small>GitHub verified</small></article>
      </section>

      <section className="admin-section telemetry-section" aria-labelledby="activity-heading">
        <div className="section-heading">
          <div><span className="header-tag header-tag-red">Live monitor</span><h2 id="activity-heading">Download activity</h2></div>
          <p>Fourteen-day history and the current download queue. Refreshes every 15 seconds while this tab is visible.</p>
        </div>

        <div className="telemetry-grid">
          <article className="activity-chart-card">
            <div className="telemetry-card-heading">
              <div><BarChart3 size={18} /><h3>Downloads started</h3></div>
              <strong>{activityTotal}<small>14 days</small></strong>
            </div>
            <div className="download-chart" role="img" aria-label={`${activityTotal} downloads started over the last 14 days`}>
              {(telemetry?.activity || []).map((point) => (
                <div className="download-bar-slot" key={point.date} title={`${point.label}: ${point.downloads} started, ${point.completed} completed, ${point.failed} failed`}>
                  <span>{point.downloads || ""}</span>
                  <i style={{ height: `${Math.round((point.downloads / activityMax) * 100)}%` }} />
                  <small>{point.label.split(" ")[1]}</small>
                </div>
              ))}
              {!telemetry && <div className="chart-empty"><Loader2 className="spin" size={20} /> Loading activity…</div>}
            </div>
          </article>

          <article className="live-downloads-card">
            <div className="telemetry-card-heading">
              <div><Download size={18} /><h3>Live queue</h3></div>
              <strong>{telemetry?.active_jobs.length ?? 0}<small>active</small></strong>
            </div>
            <div className="live-download-list">
              {(telemetry?.active_jobs || []).map((job) => {
                const progress = Math.max(0, Math.min(100, job.progress));
                return <article className="live-download-row" key={job.id}>
                  <div className="live-download-copy"><strong title={job.title}>{job.title || "Untitled download"}</strong><small>{job.status === "queued" ? "Waiting in queue" : `${Math.round(progress)}% downloading`} · {job.format.toUpperCase()}</small></div>
                  <div className="live-download-progress" aria-label={`${Math.round(progress)}% complete`}><i style={{ transform: `scaleX(${progress / 100})` }} /></div>
                </article>;
              })}
              {telemetry && telemetry.active_jobs.length === 0 && <div className="live-download-empty">No downloads are running.</div>}
              {!telemetry && <div className="live-download-empty"><Loader2 className="spin" size={18} /> Loading queue…</div>}
            </div>
          </article>
        </div>
      </section>

      <section className="admin-section" aria-labelledby="cache-heading">
        <div className="section-heading">
          <div><span className="header-tag">Storage</span><h2 id="cache-heading">Cached files</h2></div>
          <div className="section-actions">
            {selected.size > 0 && <span className="selection-readout">{selected.size} selected · {fmtBytes(selectedSize)}</span>}
            <button className="btn btn-delete" type="button" disabled={!selected.size || anyDeleteBusy} onClick={() => requestDelete([...selected])}>
              {anyDeleteBusy ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />} Delete selected
            </button>
          </div>
        </div>

        <div className="data-list cache-list">
          {files.length > 0 && <div className="data-head cache-grid">
            <button className="check-button" type="button" onClick={() => setSelected(allSelected ? new Set() : new Set(files.map((file) => file.id)))} aria-label={allSelected ? "Deselect all files" : "Select all files"}>
              {allSelected ? <CheckSquare size={18} /> : <Square size={18} />}
            </button>
            <span>File</span><span>Format</span><span>Size</span><span>Cached</span><span>Action</span>
          </div>}
          {files.map((file) => {
            const rowBusy = isBusy(`delete-${file.id}`);
            return <article className={`data-row cache-grid ${selected.has(file.id) ? "is-selected" : ""}`} key={file.id}>
              <button className="check-button" type="button" onClick={() => toggle(file.id)} aria-label={`Select ${file.title || file.file_name || file.id}`}>
                {selected.has(file.id) ? <CheckSquare size={18} /> : <Square size={18} />}
              </button>
              <div className="file-identity"><HardDrive size={16} /><span title={file.title || file.file_name}>{file.title || file.file_name || file.id}</span></div>
              <span className="mono-cell">{file.format}</span>
              <span className="mono-cell">{fmtBytes(file.size_bytes)}</span>
              <span>{new Date(file.created_at).toLocaleDateString()}</span>
              <button className="row-delete" type="button" disabled={rowBusy} onClick={() => requestDelete([file.id])} aria-label={`Delete ${file.title || file.file_name || "file"}`}>
                {rowBusy ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}<span>Delete</span>
              </button>
            </article>;
          })}
          {!loading && files.length === 0 && <div className="empty-state"><HardDrive size={32} /><p>The cache is empty.</p></div>}
          {loading && <div className="empty-state"><Loader2 className="spin" size={28} /><p>Reading cache…</p></div>}
        </div>
      </section>

      <section className="admin-section" aria-labelledby="access-heading">
        <div className="section-heading">
          <div><span className="header-tag header-tag-green">Permissions</span><h2 id="access-heading">Manage access</h2></div>
          <p>Every new account starts with 5 GB. Inbound server downloads and outbound user downloads both count.</p>
        </div>

        <div className="access-grid">
          {users.map((target) => {
            const rowBusy = isBusy(`user-${target.id}`);
            const options = limitOptions(target.usage_limit_bytes);
            return <article className="access-card" key={target.id}>
              <div className="access-card-top">
                <div className="access-identity">
                  <div className="identity-mark">{(target.name || target.email || "U").slice(0, 1).toUpperCase()}</div>
                  <div><h3>{target.name || "Unnamed user"}</h3><p>{target.github_username ? `@${target.github_username}` : target.email || "No public identity"}</p></div>
                </div>
                <span className={`role-badge ${target.is_admin ? "role-admin" : ""}`}>{target.is_owner ? "Owner" : target.is_admin ? "Admin" : "Member"}</span>
              </div>
              <div className="usage-row"><span>{fmtBytes(target.used_bytes)} used</span><span>{fmtBytes(target.usage_limit_bytes)} limit</span></div>
              <div className="usage-track"><span style={{ transform: `scaleX(${usagePercent(target) / 100})` }} /></div>
              <div className="usage-breakdown"><span>In {fmtBytes(target.ingress_bytes)}</span><span>Out {fmtBytes(target.egress_bytes)}</span><strong>{usagePercent(target)}%</strong></div>
              <div className="access-controls">
                <label><span>Usage limit</span><select value={target.usage_limit_bytes} disabled={rowBusy} onChange={(event) => void updateUser(target, { usage_limit_bytes: Number(event.target.value) })}>
                  {options.map((bytes) => <option key={bytes} value={bytes}>{formatLimitOption(bytes)}</option>)}
                </select></label>
              </div>
            </article>;
          })}
        </div>
      </section>
    </main>
  );
}
