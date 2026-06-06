import React, { useEffect, useMemo, useState } from "react";
import { useQuery, useMutation } from "convex/react";
import { useAuthActions } from "@convex-dev/auth/react";
import { api } from "../convex/_generated/api";
import type { Id } from "../convex/_generated/dataModel";
import {
  Activity,
  ArrowLeft,
  Ban,
  Check,
  Clock,
  Database,
  Download,
  ExternalLink,
  Film,
  HardDrive,
  Loader2,
  LogOut,
  Music,
  RefreshCw,
  Server,
  Shield,
  ShieldOff,
  Trash2,
  UserCheck,
  UserX,
  Users,
  Zap,
} from "lucide-react";

/* ── Types ───────────────────────────────────────────────── */

type UserRecord = {
  _id: string;
  name?: string;
  email?: string;
  isApproved: boolean;
  isAdmin: boolean;
  _creationTime: number;
};

type BannedEmail = {
  _id: string;
  email: string;
  bannedAt: number;
  bannedBy?: string;
};

type AdminJob = {
  id: string;
  url: string;
  title?: string;
  thumbnail?: string;
  format: string;
  status: "queued" | "running" | "completed" | "failed";
  progress: number;
  message?: string;
  file_name?: string;
  download_url?: string;
  created_at: string;
  updated_at: string;
  expires_at?: string;
};

type AdminData = {
  jobs: AdminJob[];
  temp: { used_bytes: number; limit_bytes: number; free_bytes: number };
  limits: { max_duration_seconds: number; max_active_jobs: number; max_queued_jobs: number; file_ttl_seconds: number };
  disk?: { total_bytes: number; used_bytes: number; free_bytes: number; percent_used: number };
  system?: { platform: string; python: string; uptime_seconds: number; pid: number };
  job_stats?: { total: number; running: number; queued: number; completed: number; failed: number };
};

type YtdlpStatus = {
  version: string;
  updating: boolean;
  log: string[];
  auto_update_hours: number;
};

/* ── Props ───────────────────────────────────────────────── */

interface AdminDashboardProps {
  user: { name?: string; email?: string; isAdmin: boolean };
  onBack: () => void;
}

/* ── Helpers ─────────────────────────────────────────────── */

function fmtBytes(b?: number): string {
  if (!b) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let val = b, idx = 0;
  while (val >= 1024 && idx < units.length - 1) { val /= 1024; idx++; }
  return `${val.toFixed(idx === 0 ? 0 : 1)} ${units[idx]}`;
}

function fmtUptime(s?: number): string {
  if (!s) return "—";
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function fmtCreation(ts: number): string {
  return new Date(ts).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

function extractVideoId(url: string): string | null {
  try {
    const u = new URL(url);
    if (u.hostname.includes("youtu.be")) return u.pathname.slice(1);
    return u.searchParams.get("v");
  } catch { return null; }
}

function getThumbnail(job: AdminJob): string | null {
  if (job.thumbnail) return job.thumbnail;
  const vid = extractVideoId(job.url);
  if (vid) return `https://i.ytimg.com/vi/${vid}/mqdefault.jpg`;
  return null;
}

type AdminTab = "overview" | "jobs" | "users" | "system";

/* ── Component ───────────────────────────────────────────── */

export function AdminDashboard({ user, onBack }: AdminDashboardProps) {
  const { signOut } = useAuthActions();
  const rawUsers = useQuery(api.users.listAllUsers);
  const users: UserRecord[] = (rawUsers as UserRecord[] | undefined) ?? [];
  const rawBanned = useQuery(api.users.listBannedEmails);
  const bannedEmails: BannedEmail[] = (rawBanned as BannedEmail[] | undefined) ?? [];

  const setApproval = useMutation(api.users.setApproval);
  const setAdmin = useMutation(api.users.setAdmin);
  const deleteUser = useMutation(api.users.deleteUser);
  const banEmail = useMutation(api.users.banEmail);
  const unbanEmail = useMutation(api.users.unbanEmail);

  const [tab, setTab] = useState<AdminTab>("overview");
  const [adminData, setAdminData] = useState<AdminData | null>(null);
  const [loading, setLoading] = useState(true);
  const [backendToken] = useState(() => localStorage.getItem("downloader_token") || "");

  // yt-dlp state
  const [ytdlpStatus, setYtdlpStatus] = useState<YtdlpStatus | null>(null);
  const [ytdlpBusy, setYtdlpBusy] = useState(false);
  const [scheduleHours, setScheduleHours] = useState(0);

  // ban form
  const [banInput, setBanInput] = useState("");
  const [banError, setBanError] = useState("");

  const authHeaders = useMemo(() => ({ Authorization: `Bearer ${backendToken}` }), [backendToken]);

  /* ── Fetch admin data ──────────────────────────────── */

  useEffect(() => {
    if (!backendToken) return;
    let cancelled = false;
    const fetchData = async () => {
      try {
        const r = await fetch("/api/admin/jobs", { headers: authHeaders });
        if (r.ok && !cancelled) setAdminData(await r.json());
      } catch { /* ignore */ }
      finally { if (!cancelled) setLoading(false); }
    };
    fetchData();
    const interval = setInterval(fetchData, 4000);
    return () => { cancelled = true; clearInterval(interval); };
  }, [backendToken, authHeaders]);

  /* ── Fetch yt-dlp status ───────────────────────────── */

  useEffect(() => {
    if (!backendToken) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const r = await fetch("/api/admin/ytdlp/status", { headers: authHeaders });
        if (r.ok && !cancelled) {
          const data: YtdlpStatus = await r.json();
          setYtdlpStatus(data);
          setScheduleHours(data.auto_update_hours);
        }
      } catch { /* ignore */ }
    };
    poll();
    const interval = setInterval(poll, 3000);
    return () => { cancelled = true; clearInterval(interval); };
  }, [backendToken, authHeaders]);

  /* ── Computed stats ────────────────────────────────── */

  const userStats = useMemo(() => {
    const total = users.length, approved = users.filter(u => u.isApproved).length;
    return { total, approved, pending: total - approved, admins: users.filter(u => u.isAdmin).length };
  }, [users]);

  const jobStats = adminData?.job_stats ?? {
    total: adminData?.jobs.length ?? 0,
    running: adminData?.jobs.filter(j => j.status === "running").length ?? 0,
    queued: adminData?.jobs.filter(j => j.status === "queued").length ?? 0,
    completed: adminData?.jobs.filter(j => j.status === "completed").length ?? 0,
    failed: adminData?.jobs.filter(j => j.status === "failed").length ?? 0,
  };

  const storagePercent = adminData?.temp
    ? Math.min(100, Math.round((adminData.temp.used_bytes / adminData.temp.limit_bytes) * 100))
    : 0;

  /* ── Handlers ──────────────────────────────────────── */

  async function handleYtdlpUpdate() {
    setYtdlpBusy(true);
    try {
      await fetch("/api/admin/ytdlp/update", { method: "POST", headers: authHeaders });
    } finally {
      setYtdlpBusy(false);
    }
  }

  async function handleScheduleSave() {
    await fetch("/api/admin/ytdlp/schedule", {
      method: "POST",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ hours: scheduleHours }),
    });
  }

  async function handleDeleteUser(userId: Id<"users">, name?: string) {
    if (!confirm(`Delete user "${name || userId}"? This cannot be undone.`)) return;
    try { await deleteUser({ userId }); } catch (e) { alert(String(e)); }
  }

  function notifyApproved(email: string, name?: string) {
    if (!backendToken || !email) return;
    fetch("/api/notify/approved", {
      method: "POST",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ email, name }),
    }).catch(() => { /* non-critical */ });
  }

  async function handleBanEmail(e: React.FormEvent) {
    setBanError("");
    const email = banInput.trim().toLowerCase();
    if (!email.includes("@")) { setBanError("Enter a valid email"); return; }
    try {
      await banEmail({ email });
      setBanInput("");
    } catch (err) { setBanError(String(err)); }
  }

  /* ── Render ────────────────────────────────────────── */

  return (
    <main className="app-shell fade-in">
      {/* Header */}
      <header className="header">
        <div className="header-left">
          <button className="btn btn-outline" type="button" onClick={onBack}>
            <ArrowLeft size={14} /> Back
          </button>
          <div className="header-text">
            <span className="header-tag header-tag-red">Admin</span>
            <h1>Dashboard</h1>
          </div>
        </div>
        <div className="header-actions">
          <button className="btn btn-dark" type="button" onClick={() => signOut()}>
            <LogOut size={14} /> Sign Out
          </button>
        </div>
      </header>

      {/* Tab Navigation */}
      <div className="admin-tabs">
        {(["overview", "jobs", "users", "system"] as AdminTab[]).map((t) => (
          <button key={t} className={`admin-tab-btn ${tab === t ? "active" : ""}`} onClick={() => setTab(t)}>
            {t === "overview" && <Activity size={14} />}
            {t === "jobs" && <Download size={14} />}
            {t === "users" && <Users size={14} />}
            {t === "system" && <Server size={14} />}
            {t.charAt(0).toUpperCase() + t.slice(1)}
            {t === "jobs" && jobStats.running > 0 && <span className="tab-counter">{jobStats.running}</span>}
            {t === "users" && userStats.pending > 0 && <span className="tab-counter tab-counter-red">{userStats.pending}</span>}
          </button>
        ))}
      </div>

      {/* ═══ OVERVIEW TAB ═══ */}
      {tab === "overview" && (
        <div className="admin-content fade-in">
          <div className="stats-grid">
            <div className="stat-card stat-blue"><div className="stat-icon"><Download size={22} /></div><div className="stat-content"><div className="stat-value">{jobStats.total}</div><div className="stat-label">Total Jobs</div></div></div>
            <div className="stat-card stat-green"><div className="stat-icon"><Check size={22} /></div><div className="stat-content"><div className="stat-value">{jobStats.completed}</div><div className="stat-label">Completed</div></div></div>
            <div className="stat-card stat-yellow"><div className="stat-icon"><Zap size={22} /></div><div className="stat-content"><div className="stat-value">{jobStats.running}</div><div className="stat-label">Running</div></div></div>
            <div className="stat-card stat-red"><div className="stat-icon"><UserX size={22} /></div><div className="stat-content"><div className="stat-value">{jobStats.failed}</div><div className="stat-label">Failed</div></div></div>
          </div>

          <div className="admin-panels">
            {/* Storage */}
            <div className="admin-panel">
              <div className="panel-header"><HardDrive size={16} /><h3>Storage</h3></div>
              <div className="panel-body">
                <div className="storage-bar-container">
                  <div className="storage-bar-bg"><div className="storage-bar-fill" style={{ width: `${storagePercent}%` }} /></div>
                  <span className="storage-percent">{storagePercent}%</span>
                </div>
                <div className="panel-stat-row"><span>Used</span><strong>{fmtBytes(adminData?.temp.used_bytes)}</strong></div>
                <div className="panel-stat-row"><span>Limit</span><strong>{fmtBytes(adminData?.temp.limit_bytes)}</strong></div>
                {adminData?.disk && (<>
                  <div className="panel-divider" />
                  <div className="panel-stat-row"><span>Disk Total</span><strong>{fmtBytes(adminData.disk.total_bytes)}</strong></div>
                  <div className="panel-stat-row"><span>Disk Free</span><strong>{fmtBytes(adminData.disk.free_bytes)}</strong></div>
                  <div className="panel-stat-row"><span>Disk Used</span><strong>{adminData.disk.percent_used}%</strong></div>
                </>)}
              </div>
            </div>

            {/* System */}
            <div className="admin-panel">
              <div className="panel-header"><Server size={16} /><h3>System</h3></div>
              <div className="panel-body">
                {adminData?.system ? (<>
                  <div className="panel-stat-row"><span>Platform</span><strong>{adminData.system.platform}</strong></div>
                  <div className="panel-stat-row"><span>Python</span><strong>{adminData.system.python}</strong></div>
                  <div className="panel-stat-row"><span>Uptime</span><strong>{fmtUptime(adminData.system.uptime_seconds)}</strong></div>
                  <div className="panel-stat-row"><span>PID</span><strong>{adminData.system.pid}</strong></div>
                </>) : <div className="panel-stat-row"><span>Loading…</span><Loader2 className="spin" size={14} /></div>}
                <div className="panel-divider" />
                <div className="panel-stat-row"><span>Max Concurrent</span><strong>{adminData?.limits.max_active_jobs ?? "—"}</strong></div>
                <div className="panel-stat-row"><span>Max Queue</span><strong>{adminData?.limits.max_queued_jobs ?? "—"}</strong></div>
                <div className="panel-stat-row"><span>File TTL</span><strong>{adminData?.limits.file_ttl_seconds ? `${Math.round(adminData.limits.file_ttl_seconds / 60)}m` : "—"}</strong></div>
              </div>
            </div>

            {/* Users Summary */}
            <div className="admin-panel">
              <div className="panel-header"><Users size={16} /><h3>Users</h3></div>
              <div className="panel-body">
                <div className="panel-stat-row"><span>Total</span><strong>{userStats.total}</strong></div>
                <div className="panel-stat-row"><span>Approved</span><strong className="text-green">{userStats.approved}</strong></div>
                <div className="panel-stat-row"><span>Pending</span><strong className="text-red">{userStats.pending}</strong></div>
                <div className="panel-stat-row"><span>Admins</span><strong className="text-yellow">{userStats.admins}</strong></div>
                <div className="panel-divider" />
                <div className="panel-stat-row"><span>Banned emails</span><strong className="text-red">{bannedEmails.length}</strong></div>
              </div>
            </div>
          </div>

          {/* Recent Activity */}
          <section className="admin-section">
            <div className="queue-header"><span className="header-tag">Recent</span><h2>Activity</h2></div>
            <div className="admin-activity-list">
              {loading ? (
                <div className="admin-table-empty"><Loader2 className="spin" size={28} /><p>Loading…</p></div>
              ) : adminData?.jobs.slice(0, 8).map((job) => {
                const thumb = getThumbnail(job);
                return (
                  <div className="activity-row" key={job.id}>
                    {thumb ? <img className="activity-thumb" src={thumb} alt="" loading="lazy" /> : <div className="activity-thumb-placeholder">{job.format.includes("audio") || job.format === "mp3" ? <Music size={16} /> : <Film size={16} />}</div>}
                    <div className="activity-info">
                      <strong>{job.title || job.file_name || job.url}</strong>
                      <span className="activity-meta">
                        <span className={`badge badge-sm ${job.status}`}>{job.status}</span>
                        <span>{job.format.toUpperCase()}</span>
                        <span>{relativeTime(job.created_at)}</span>
                      </span>
                    </div>
                    {job.status === "running" && (
                      <div className="activity-progress">
                        <div className="activity-progress-bar"><div className="progress-fill" style={{ width: `${Math.min(100, job.progress)}%` }} /></div>
                        <span>{Math.round(job.progress)}%</span>
                      </div>
                    )}
                  </div>
                );
              })}
              {!loading && !adminData?.jobs.length && <div className="admin-table-empty"><Download size={28} /><p>No activity yet</p></div>}
            </div>
          </section>
        </div>
      )}

      {/* ═══ JOBS TAB ═══ */}
      {tab === "jobs" && (
        <div className="admin-content fade-in">
          <section className="admin-section">
            <div className="queue-header"><span className="header-tag">Monitor</span><h2>All Jobs ({adminData?.jobs.length ?? 0})</h2></div>
            <div className="admin-jobs-grid">
              {loading ? <div className="admin-table-empty"><Loader2 className="spin" size={28} /><p>Loading…</p></div>
                : adminData?.jobs.map((job) => {
                  const thumb = getThumbnail(job);
                  return (
                    <div className={`admin-job-card admin-job-${job.status}`} key={job.id}>
                      <div className="admin-job-visual">
                        {thumb ? <img className="admin-job-thumb" src={thumb} alt="" loading="lazy" /> : <div className="admin-job-thumb-empty">{job.format.includes("audio") || job.format === "mp3" ? <Music size={24} /> : <Film size={24} />}</div>}
                        <span className={`badge badge-overlay ${job.status}`}>{job.status}</span>
                      </div>
                      <div className="admin-job-details">
                        <h4 className="admin-job-title">{job.title || job.file_name || "Untitled"}</h4>
                        <div className="admin-job-meta">
                          <span className="meta-chip">{job.format.toUpperCase()}</span>
                          <span className="meta-chip">{fmtDate(job.created_at)}</span>
                          {job.expires_at && <span className="meta-chip meta-chip-warn"><Clock size={10} /> Expires {relativeTime(job.expires_at)}</span>}
                        </div>
                        {job.status === "running" && <div className="progress-bar" style={{ marginTop: 8 }}><div className="progress-fill" style={{ width: `${Math.min(100, job.progress)}%` }} /></div>}
                        {job.message && <p className="admin-job-msg">{job.message}</p>}
                        <div className="admin-job-url">
                          <a href={job.url} target="_blank" rel="noopener noreferrer"><ExternalLink size={10} /> Source</a>
                          {job.download_url && <a className="admin-job-dl" href={`${job.download_url}?token=${backendToken}`} download><Download size={12} /> Download</a>}
                        </div>
                      </div>
                    </div>
                  );
                })}
              {!loading && !adminData?.jobs.length && <div className="admin-table-empty"><Download size={28} /><p>No jobs recorded</p></div>}
            </div>
          </section>
        </div>
      )}

      {/* ═══ USERS TAB ═══ */}
      {tab === "users" && (
        <div className="admin-content fade-in">
          <div className="stats-grid">
            <div className="stat-card stat-blue"><div className="stat-icon"><Users size={22} /></div><div className="stat-content"><div className="stat-value">{userStats.total}</div><div className="stat-label">Total Users</div></div></div>
            <div className="stat-card stat-green"><div className="stat-icon"><UserCheck size={22} /></div><div className="stat-content"><div className="stat-value">{userStats.approved}</div><div className="stat-label">Approved</div></div></div>
            <div className="stat-card stat-red"><div className="stat-icon"><UserX size={22} /></div><div className="stat-content"><div className="stat-value">{userStats.pending}</div><div className="stat-label">Pending</div></div></div>
            <div className="stat-card stat-yellow"><div className="stat-icon"><Shield size={22} /></div><div className="stat-content"><div className="stat-value">{userStats.admins}</div><div className="stat-label">Admins</div></div></div>
          </div>

          {/* User Table */}
          <section className="admin-section">
            <div className="queue-header"><span className="header-tag">Users</span><h2>Manage Access</h2></div>
            <div className="admin-table">
              <div className="admin-table-head">
                <span>User</span><span>Email</span><span>Joined</span><span>Status</span><span>Actions</span>
              </div>
              {users.map((u) => (
                <div className="admin-table-row" key={u._id}>
                  <span className="cell-name">{u.name || "—"}</span>
                  <span className="cell-email">{u.email || "—"}</span>
                  <span className="cell-date">{fmtCreation(u._creationTime)}</span>
                  <span className="cell-status">
                    <span className={`badge ${u.isApproved ? "completed" : "failed"}`}>{u.isApproved ? "Approved" : "Pending"}</span>
                    {u.isAdmin && <span className="badge running admin-badge">Admin</span>}
                  </span>
                  <span className="cell-actions">
                    {u.isApproved
                      ? <button className="action-btn action-danger" onClick={() => setApproval({ userId: u._id as Id<"users">, approved: false })} title="Revoke access"><UserX size={14} /><span>Revoke</span></button>
                      : <button className="action-btn action-success" onClick={() => { setApproval({ userId: u._id as Id<"users">, approved: true }); if (u.email) notifyApproved(u.email, u.name); }} title="Approve access"><Check size={14} /><span>Approve</span></button>
                    }
                    <button className={`action-btn ${u.isAdmin ? "action-danger" : "action-neutral"}`} onClick={() => setAdmin({ userId: u._id as Id<"users">, isAdmin: !u.isAdmin })} title={u.isAdmin ? "Remove admin" : "Make admin"}>
                      {u.isAdmin ? <ShieldOff size={14} /> : <Shield size={14} />}
                    </button>
                    {u.email && (
                      <button className="action-btn action-danger" onClick={() => banEmail({ email: u.email! })} title={`Ban ${u.email}`}>
                        <Ban size={14} />
                      </button>
                    )}
                    <button className="action-btn action-danger" onClick={() => handleDeleteUser(u._id as Id<"users">, u.name)} title="Delete user">
                      <Trash2 size={14} />
                    </button>
                  </span>
                </div>
              ))}
              {users.length === 0 && <div className="admin-table-empty"><Users size={28} /><p>No users registered yet</p></div>}
            </div>
          </section>

          {/* Ban email form */}
          <section className="admin-section">
            <div className="queue-header"><span className="header-tag">Block</span><h2>Ban Email Address</h2></div>
            <form className="admin-ban-form" onSubmit={handleBanEmail}>
              <div className="input-box" style={{ flex: 1 }}>
                <Ban size={16} />
                <input
                  value={banInput}
                  onChange={e => setBanInput(e.target.value)}
                  placeholder="user@gmail.com"
                  type="email"
                  autoComplete="off"
                />
              </div>
              <button className="btn btn-primary" type="submit" disabled={!banInput.trim()}>
                <Ban size={14} /> Ban
              </button>
            </form>
            {banError && <p className="error-msg fade-in">{banError}</p>}

            {/* Banned list */}
            {bannedEmails.length > 0 && (
              <div className="admin-table" style={{ marginTop: 16 }}>
                <div className="admin-table-head">
                  <span>Email</span><span>Banned</span><span>By</span><span>Actions</span>
                </div>
                {bannedEmails.map((b) => (
                  <div className="admin-table-row" key={b._id}>
                    <span className="cell-email">{b.email}</span>
                    <span className="cell-date">{fmtCreation(b.bannedAt)}</span>
                    <span className="cell-email">{b.bannedBy || "—"}</span>
                    <span className="cell-actions">
                      <button className="action-btn action-success" onClick={() => unbanEmail({ email: b.email })} title="Unban">
                        <Check size={14} /><span>Unban</span>
                      </button>
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {/* ═══ SYSTEM TAB ═══ */}
      {tab === "system" && (
        <div className="admin-content fade-in">
          {/* yt-dlp panel */}
          <section className="admin-section">
            <div className="queue-header"><span className="header-tag">yt-dlp</span><h2>Updater</h2></div>
            <div className="admin-panel" style={{ maxWidth: 520 }}>
              <div className="panel-body">
                <div className="panel-stat-row">
                  <span>Current version</span>
                  <strong>{ytdlpStatus?.version || "—"}</strong>
                </div>
                <div className="panel-stat-row">
                  <span>Status</span>
                  <strong>{ytdlpStatus?.updating ? <span className="badge running">Updating…</span> : "Idle"}</strong>
                </div>
                <div className="panel-divider" />

                {/* Manual update */}
                <div className="panel-stat-row" style={{ alignItems: "flex-start", flexDirection: "column", gap: 8 }}>
                  <span style={{ fontWeight: 600 }}>Manual Update</span>
                  <button
                    className="btn btn-primary"
                    onClick={handleYtdlpUpdate}
                    disabled={ytdlpBusy || ytdlpStatus?.updating}
                    style={{ width: "auto" }}
                  >
                    {ytdlpStatus?.updating ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
                    {ytdlpStatus?.updating ? "Updating…" : "Update Now"}
                  </button>
                </div>

                <div className="panel-divider" />

                {/* Auto-update schedule */}
                <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                  <span style={{ fontWeight: 600 }}>Auto-update schedule</span>
                  <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                    <select
                      className="input-box"
                      value={scheduleHours}
                      onChange={e => setScheduleHours(Number(e.target.value))}
                      style={{ flex: 1, padding: "8px 12px", border: "2px solid var(--border)", background: "var(--bg)", cursor: "pointer" }}
                    >
                      <option value={0}>Off</option>
                      <option value={12}>Every 12 hours</option>
                      <option value={24}>Every 24 hours</option>
                      <option value={48}>Every 48 hours</option>
                      <option value={168}>Every week</option>
                    </select>
                    <button className="btn btn-outline" onClick={handleScheduleSave} style={{ width: "auto" }}>
                      <Check size={14} /> Save
                    </button>
                  </div>
                  {ytdlpStatus && ytdlpStatus.auto_update_hours > 0 && (
                    <p style={{ fontSize: 12, opacity: 0.6, margin: 0 }}>
                      Auto-updates every {ytdlpStatus.auto_update_hours}h
                    </p>
                  )}
                </div>

                {/* Update log */}
                {ytdlpStatus?.log && ytdlpStatus.log.length > 0 && (
                  <>
                    <div className="panel-divider" />
                    <div style={{ fontWeight: 600, marginBottom: 6 }}>Update log</div>
                    <pre style={{ fontSize: 11, background: "var(--bg-alt, #111)", padding: 10, overflowX: "auto", maxHeight: 180, margin: 0, border: "1px solid var(--border)" }}>
                      {ytdlpStatus.log.join("\n")}
                    </pre>
                  </>
                )}
              </div>
            </div>
          </section>
        </div>
      )}

      <footer className="watermark">
        made by{" "}
        <a href="https://github.com/YashasVM" target="_blank" rel="noopener noreferrer">@yashas.vm</a>
      </footer>
    </main>
  );
}
