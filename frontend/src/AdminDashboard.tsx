import { UserButton, useClerk } from "@clerk/react";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ArrowLeft,
  CheckSquare,
  Database,
  HardDrive,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Square,
  Trash2,
  Users,
} from "lucide-react";
import type { AppUser } from "./types";

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

interface AdminDashboardProps {
  user: AppUser;
  token: string;
  onBack: () => void;
}

function fmtBytes(bytes: number): string {
  if (bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** unit).toFixed(unit < 2 ? 0 : 1)} ${units[unit]}`;
}

function usagePercent(user: AppUser): number {
  return Math.min(100, Math.round((user.used_bytes / Math.max(1, user.usage_limit_bytes)) * 100));
}

export function AdminDashboard({ user, token, onBack }: AdminDashboardProps) {
  const { signOut } = useClerk();
  const [files, setFiles] = useState<CachedFile[]>([]);
  const [users, setUsers] = useState<AppUser[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState("");
  const [notice, setNotice] = useState<Notice | null>(null);

  const request = useCallback(async <T,>(path: string, init?: RequestInit): Promise<T> => {
    const response = await fetch(path, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...init?.headers },
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.detail || "Request failed");
    return body as T;
  }, [token]);

  const reload = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const [fileRows, userRows] = await Promise.all([
        request<CachedFile[]>("/api/admin/files"),
        request<AppUser[]>("/api/admin/users"),
      ]);
      setFiles(fileRows);
      setUsers(userRows);
      setSelected((current) => new Set([...current].filter((id) => fileRows.some((file) => file.id === id))));
    } catch (error) {
      setNotice({ text: error instanceof Error ? error.message : "Could not load the dashboard", kind: "error" });
    } finally {
      setLoading(false);
    }
  }, [request, token]);

  useEffect(() => { void reload(); }, [reload]);

  const totalCache = useMemo(() => files.reduce((sum, file) => sum + file.size_bytes, 0), [files]);
  const selectedSize = useMemo(
    () => files.reduce((sum, file) => sum + (selected.has(file.id) ? file.size_bytes : 0), 0),
    [files, selected],
  );
  const allSelected = files.length > 0 && selected.size === files.length;

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  async function deleteFiles(ids: string[]) {
    if (!ids.length || !window.confirm(`Delete ${ids.length} cached file${ids.length === 1 ? "" : "s"}? This cannot be undone.`)) return;
    setBusyKey("delete-files");
    try {
      const result = await request<{ deleted: number; freed_bytes: number }>("/api/admin/files", {
        method: "DELETE",
        body: JSON.stringify(ids),
      });
      setNotice({ text: `Deleted ${result.deleted} file(s) and freed ${fmtBytes(result.freed_bytes)}.`, kind: "success" });
      setSelected(new Set());
      await reload();
    } catch (error) {
      setNotice({ text: error instanceof Error ? error.message : "Delete failed", kind: "error" });
    } finally {
      setBusyKey("");
    }
  }

  async function updateUser(target: AppUser, patch: { usage_limit_bytes?: number }) {
    setBusyKey(`user-${target.id}`);
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
      setBusyKey("");
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

      <section className="admin-summary" aria-label="Dashboard summary">
        <article><Database size={22} /><span>Cache</span><strong>{fmtBytes(totalCache)}</strong><small>{files.length} file(s)</small></article>
        <article><Users size={22} /><span>Accounts</span><strong>{users.length}</strong><small>{users.filter((item) => item.is_admin).length} admin(s)</small></article>
        <article><ShieldCheck size={22} /><span>Owner</span><strong>@{user.github_username || "YashasVM"}</strong><small>GitHub verified</small></article>
      </section>

      <section className="admin-section" aria-labelledby="cache-heading">
        <div className="section-heading">
          <div><span className="header-tag">Storage</span><h2 id="cache-heading">Cached files</h2></div>
          <div className="section-actions">
            {selected.size > 0 && <span className="selection-readout">{selected.size} selected · {fmtBytes(selectedSize)}</span>}
            <button className="btn btn-delete" type="button" disabled={!selected.size || busyKey === "delete-files"} onClick={() => void deleteFiles([...selected])}>
              {busyKey === "delete-files" ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />} Delete selected
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
          {files.map((file) => <article className={`data-row cache-grid ${selected.has(file.id) ? "is-selected" : ""}`} key={file.id}>
            <button className="check-button" type="button" onClick={() => toggle(file.id)} aria-label={`Select ${file.title || file.file_name || file.id}`}>
              {selected.has(file.id) ? <CheckSquare size={18} /> : <Square size={18} />}
            </button>
            <div className="file-identity"><HardDrive size={16} /><span title={file.title || file.file_name}>{file.title || file.file_name || file.id}</span></div>
            <span className="mono-cell">{file.format}</span>
            <span className="mono-cell">{fmtBytes(file.size_bytes)}</span>
            <span>{new Date(file.created_at).toLocaleDateString()}</span>
            <button className="row-delete" type="button" onClick={() => void deleteFiles([file.id])} aria-label={`Delete ${file.title || file.file_name || "file"}`}><Trash2 size={16} /><span>Delete</span></button>
          </article>)}
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
            const isBusy = busyKey === `user-${target.id}`;
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
                <label><span>Usage limit</span><select value={target.usage_limit_bytes} disabled={isBusy} onChange={(event) => void updateUser(target, { usage_limit_bytes: Number(event.target.value) })}>
                  {[5, 10, 25, 50, 100, 250].map((gb) => <option key={gb} value={gb * 1024 ** 3}>{gb} GB</option>)}
                </select></label>
              </div>
            </article>;
          })}
        </div>
      </section>
    </main>
  );
}
