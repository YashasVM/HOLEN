import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useQuery, useMutation } from "convex/react";
import { useAuthActions } from "@convex-dev/auth/react";
import { api } from "../convex/_generated/api";
import type { Id } from "../convex/_generated/dataModel";
import {
  ArrowLeft,
  Check,
  CheckSquare,
  HardDrive,
  List,
  Loader2,
  LogOut,
  RefreshCw,
  Shield,
  ShieldOff,
  Square,
  Trash2,
  UserCheck,
  UserX,
  Users,
  Send,
} from "lucide-react";

type UserRecord = {
  _id: string;
  name?: string;
  email?: string;
  isApproved: boolean;
  isAdmin: boolean;
  _creationTime: number;
};

type CachedFile = {
  id: string;
  title?: string;
  file_name?: string;
  format: string;
  created_at: string;
  expires_at?: string;
  size_bytes: number;
};

interface AdminDashboardProps {
  user: { name?: string; email?: string; isAdmin: boolean };
  onBack: () => void;
}

function fmtBytes(b: number): string {
  if (b === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let v = b, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

export function AdminDashboard({ user, onBack }: AdminDashboardProps) {
  const { signOut } = useAuthActions();
  const rawUsers = useQuery(api.users.listAllUsers);
  const users: UserRecord[] = (rawUsers as UserRecord[] | undefined) ?? [];
  const setApproval = useMutation(api.users.setApproval);
  const setAdmin = useMutation(api.users.setAdmin);

  const [files, setFiles] = useState<CachedFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleting, setDeleting] = useState(false);
  const [deleteMsg, setDeleteMsg] = useState<string | null>(null);
  const [plexStatus, setPlexStatus] = useState<Record<string, "sending" | "done" | "error">>({});
  const [toast, setToast] = useState<{ msg: string; ok: boolean } | null>(null);
  const [jobs, setJobs] = useState<any[]>([]);
  const [jobsLoading, setJobsLoading] = useState(false);
  const [clearing, setClearing] = useState(false);

  const token = localStorage.getItem("downloader_token") || "";

  function showToast(msg: string, ok: boolean) {
    setToast({ msg, ok });
    setTimeout(() => setToast(null), 4000);
  }

  const loadJobs = useCallback(async () => {
    if (!token) return;
    setJobsLoading(true);
    try {
      const r = await fetch("/api/admin/jobs", { headers: { Authorization: `Bearer ${token}` } });
      if (r.ok) { const d = await r.json(); setJobs(d.jobs ?? []); }
    } finally { setJobsLoading(false); }
  }, [token]);

  useEffect(() => { loadJobs(); }, [loadJobs]);

  async function clearJobs() {
    setClearing(true);
    try {
      const r = await fetch("/api/admin/jobs/clear", { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
      if (r.ok) { const d = await r.json(); showToast(`Cleared ${d.deleted} job(s)`, true); await loadJobs(); }
      else showToast("Clear failed", false);
    } catch { showToast("Clear failed", false); }
    finally { setClearing(false); }
  }

  const loadFiles = useCallback(async () => {
    if (!token) return;
    setFilesLoading(true);
    try {
      const r = await fetch("/api/admin/files", { headers: { Authorization: `Bearer ${token}` } });
      if (r.ok) setFiles(await r.json());
    } finally {
      setFilesLoading(false);
    }
  }, [token]);

  useEffect(() => { loadFiles(); }, [loadFiles]);

  async function deleteSelected() {
    if (selectedIds.size === 0) return;
    setDeleting(true);
    setDeleteMsg(null);
    try {
      const r = await fetch("/api/admin/files", {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify([...selectedIds]),
      });
      const data = await r.json();
      setDeleteMsg(`Deleted ${data.deleted} file(s), freed ${fmtBytes(data.freed_bytes)}`);
      setSelectedIds(new Set());
      await loadFiles();
    } catch {
      setDeleteMsg("Delete failed");
    } finally {
      setDeleting(false);
    }
  }

  function toggleFile(id: string) {
    setSelectedIds((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  }

  async function sendToPlex(id: string, fileName?: string) {
    setPlexStatus((prev) => ({ ...prev, [id]: "sending" }));
    try {
      const r = await fetch(`/api/admin/files/${id}/send-to-plex`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      });
      if (r.ok) {
        setPlexStatus((prev) => ({ ...prev, [id]: "done" }));
        showToast(`✓ Sent "${fileName || id}" to Plex`, true);
      } else {
        const data = await r.json().catch(() => ({}));
        setPlexStatus((prev) => ({ ...prev, [id]: "error" }));
        showToast(`✗ Failed: ${data.detail || r.statusText}`, false);
      }
    } catch (e: any) {
      setPlexStatus((prev) => ({ ...prev, [id]: "error" }));
      showToast(`✗ Network error: ${e?.message || "unknown"}`, false);
    }
  }

  function toggleAll() {
    setSelectedIds(selectedIds.size === files.length ? new Set() : new Set(files.map((f) => f.id)));
  }

  const totalSize = useMemo(() => files.reduce((a, f) => a + f.size_bytes, 0), [files]);
  const selectedSize = useMemo(() => files.filter((f) => selectedIds.has(f.id)).reduce((a, f) => a + f.size_bytes, 0), [files, selectedIds]);
  const allSelected = files.length > 0 && selectedIds.size === files.length;

  const stats = useMemo(() => {
    const total = users.length;
    const approved = users.filter((u) => u.isApproved).length;
    return { total, approved, pending: total - approved, admins: users.filter((u) => u.isAdmin).length };
  }, [users]);

  function formatDate(ts: number): string {
    return new Date(ts).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
  }

  async function handleApproval(userId: Id<"users">, approved: boolean) {
    try { await setApproval({ userId, approved }); } catch (err) { console.error(err); }
  }

  async function handleAdminToggle(userId: Id<"users">, isAdmin: boolean) {
    try { await setAdmin({ userId, isAdmin }); } catch (err) { console.error(err); }
  }

  return (
    <main className="app-shell fade-in">
      {toast && <div className={`toast ${toast.ok ? "toast-ok" : "toast-err"}`}>{toast.msg}</div>}
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

      {/* Stats */}
      <div className="stats-grid">
        <div className="stat-card stat-blue">
          <div className="stat-icon"><Users size={22} /></div>
          <div className="stat-content"><div className="stat-value">{stats.total}</div><div className="stat-label">Total Users</div></div>
        </div>
        <div className="stat-card stat-green">
          <div className="stat-icon"><UserCheck size={22} /></div>
          <div className="stat-content"><div className="stat-value">{stats.approved}</div><div className="stat-label">Approved</div></div>
        </div>
        <div className="stat-card stat-red">
          <div className="stat-icon"><UserX size={22} /></div>
          <div className="stat-content"><div className="stat-value">{stats.pending}</div><div className="stat-label">Pending</div></div>
        </div>
        <div className="stat-card stat-yellow">
          <div className="stat-icon"><Shield size={22} /></div>
          <div className="stat-content"><div className="stat-value">{stats.admins}</div><div className="stat-label">Admins</div></div>
        </div>
      </div>

      {/* Cache Management */}
      <section className="admin-section">
        <div className="queue-header">
          <span className="header-tag header-tag-red">Storage</span>
          <h2>Cached Files</h2>
        </div>

        {/* Toolbar */}
        <div className="cache-toolbar">
          <div className="cache-toolbar-left">
            <span className="cache-info"><HardDrive size={14} /> {files.length} file(s) · {fmtBytes(totalSize)} total</span>
            {selectedIds.size > 0 && <span className="cache-selected">{selectedIds.size} selected · {fmtBytes(selectedSize)}</span>}
          </div>
          <div className="cache-toolbar-right">
            <button className="btn btn-outline" type="button" onClick={loadFiles} disabled={filesLoading} title="Refresh">
              {filesLoading ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
            </button>
            <button
              className="btn btn-delete"
              type="button"
              onClick={deleteSelected}
              disabled={deleting || selectedIds.size === 0}
            >
              {deleting ? <Loader2 className="spin" size={14} /> : <Trash2 size={14} />}
              Delete {selectedIds.size > 0 ? `(${selectedIds.size})` : ""}
            </button>
          </div>
        </div>

        {deleteMsg && <p className="cache-msg fade-in">{deleteMsg}</p>}

        {!token && <p className="cache-msg">No API token — enter app password on the downloader page first.</p>}

        {/* File list */}
        <div className="cache-file-list">
          {files.length > 0 && (
            <div className="cache-file-head">
              <button className="entry-check-btn" type="button" onClick={toggleAll}>
                {allSelected ? <CheckSquare size={15} /> : <Square size={15} />}
              </button>
              <span>Title / File</span>
              <span>Format</span>
              <span>Size</span>
              <span>Added</span>
              <span>Plex</span>
            </div>
          )}
          {files.map((f) => {
            const checked = selectedIds.has(f.id);
            const ps = plexStatus[f.id];
            return (
              <div className={`cache-file-row ${checked ? "selected" : ""}`} key={f.id} onClick={() => toggleFile(f.id)}>
                <span className="entry-check-btn">
                  {checked ? <CheckSquare size={15} /> : <Square size={15} />}
                </span>
                <span className="cache-file-name">{f.title || f.file_name || f.id}</span>
                <span className="cache-file-fmt">{f.format}</span>
                <span className="cache-file-size">{fmtBytes(f.size_bytes)}</span>
                <span className="cache-file-date">{new Date(f.created_at).toLocaleDateString()}</span>
                <span className="cache-file-plex" onClick={(e) => e.stopPropagation()}>
                  <button
                    className={`btn btn-plex${ps === "done" ? " btn-plex-done" : ps === "error" ? " btn-plex-error" : ""}`}
                    type="button"
                    title="Send to Plex"
                    disabled={ps === "sending" || ps === "done"}                    onClick={() => sendToPlex(f.id, f.title || f.file_name)}
                  >
                    {ps === "sending" ? <Loader2 className="spin" size={13} /> : ps === "done" ? <Check size={13} /> : <Send size={13} />}
                    {ps === "done" ? "Sent" : ps === "error" ? "Retry" : "Plex"}
                  </button>
                </span>
              </div>
            );
          })}
          {!filesLoading && files.length === 0 && (
            <div className="admin-table-empty"><HardDrive size={28} /><p>No cached files</p></div>
          )}
        </div>
      </section>

      {/* Job History */}
      <section className="admin-section">
        <div className="cache-toolbar">
          <div className="cache-toolbar-left">
            <span className="header-tag"><List size={12} /></span>
            <h2 style={{ margin: 0, fontSize: "1.1rem" }}>Job History</h2>
            <span className="cache-info">{jobs.length} job(s)</span>
          </div>
          <div className="cache-toolbar-right">
            <button className="btn btn-outline" type="button" onClick={loadJobs} disabled={jobsLoading} title="Refresh">
              {jobsLoading ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
            </button>
            <button className="btn btn-delete" type="button" onClick={clearJobs} disabled={clearing || jobs.length === 0}>
              {clearing ? <Loader2 className="spin" size={14} /> : <Trash2 size={14} />} Clear All
            </button>
          </div>
        </div>
        <div className="cache-file-list">
          <div className="cache-file-head" style={{ gridTemplateColumns: "1fr 80px 80px 100px" }}>
            <span>Title</span><span>Format</span><span>Status</span><span>Date</span>
          </div>
          {jobs.map((j) => (
            <div className="cache-file-row" key={j.id} style={{ gridTemplateColumns: "1fr 80px 80px 100px" }}>
              <span className="cache-file-name">{j.title || j.url}</span>
              <span className="cache-file-fmt">{j.format}</span>
              <span><span className={`badge ${j.status}`}>{j.status}</span></span>
              <span className="cache-file-date">{new Date(j.created_at).toLocaleDateString()}</span>
            </div>
          ))}
          {!jobsLoading && jobs.length === 0 && (
            <div className="admin-table-empty"><List size={28} /><p>No jobs yet</p></div>
          )}
        </div>
      </section>

      {/* User Management */}
      <section className="admin-section">
        <div className="queue-header">
          <span className="header-tag">Users</span>
          <h2>Manage Access</h2>
        </div>
        <div className="admin-table">
          <div className="admin-table-head">
            <span>User</span><span>Email</span><span>Joined</span><span>Status</span><span>Actions</span>
          </div>
          {users.map((u) => (
            <div className="admin-table-row" key={u._id}>
              <span className="cell-name">{u.name || "—"}</span>
              <span className="cell-email">{u.email || "—"}</span>
              <span className="cell-date">{formatDate(u._creationTime)}</span>
              <span className="cell-status">
                <span className={`badge ${u.isApproved ? "completed" : "failed"}`}>{u.isApproved ? "Approved" : "Pending"}</span>
                {u.isAdmin && <span className="badge running admin-badge">Admin</span>}
              </span>
              <span className="cell-actions">
                {u.isApproved
                  ? <button className="action-btn action-danger" onClick={() => handleApproval(u._id as Id<"users">, false)} title="Revoke"><UserX size={14} /><span>Revoke</span></button>
                  : <button className="action-btn action-success" onClick={() => handleApproval(u._id as Id<"users">, true)} title="Approve"><Check size={14} /><span>Approve</span></button>}
                <button className={`action-btn ${u.isAdmin ? "action-danger" : "action-neutral"}`} onClick={() => handleAdminToggle(u._id as Id<"users">, !u.isAdmin)}>
                  {u.isAdmin ? <ShieldOff size={14} /> : <Shield size={14} />}
                </button>
              </span>
            </div>
          ))}
          {users.length === 0 && <div className="admin-table-empty"><Users size={28} /><p>No users registered yet</p></div>}
        </div>
      </section>
    </main>
  );
}
