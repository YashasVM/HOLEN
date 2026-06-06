import React, { useMemo } from "react";
import { useQuery, useMutation } from "convex/react";
import { useAuthActions } from "@convex-dev/auth/react";
import { api } from "../convex/_generated/api";
import type { Id } from "../convex/_generated/dataModel";
import {
  ArrowLeft,
  Check,
  LogOut,
  Shield,
  ShieldOff,
  UserCheck,
  UserX,
  Users,
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

/* ── Props ───────────────────────────────────────────────── */

interface AdminDashboardProps {
  user: { name?: string; email?: string; isAdmin: boolean };
  onBack: () => void;
}

/* ── Component ───────────────────────────────────────────── */

export function AdminDashboard({ user, onBack }: AdminDashboardProps) {
  const { signOut } = useAuthActions();
  const rawUsers = useQuery(api.users.listAllUsers);
  const users: UserRecord[] = (rawUsers as UserRecord[] | undefined) ?? [];
  const setApproval = useMutation(api.users.setApproval);
  const setAdmin = useMutation(api.users.setAdmin);

  /* ── Computed stats ─────────────────────────────────── */

  const stats = useMemo(() => {
    const total = users.length;
    const approved = users.filter((u) => u.isApproved).length;
    const pending = total - approved;
    const admins = users.filter((u) => u.isAdmin).length;
    return { total, approved, pending, admins };
  }, [users]);

  /* ── Helpers ────────────────────────────────────────── */

  function formatDate(ts: number): string {
    return new Date(ts).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    });
  }

  async function handleApproval(userId: Id<"users">, approved: boolean) {
    try {
      await setApproval({ userId, approved });
    } catch (err) {
      console.error("Failed to update approval:", err);
    }
  }

  async function handleAdminToggle(userId: Id<"users">, isAdmin: boolean) {
    try {
      await setAdmin({ userId, isAdmin });
    } catch (err) {
      console.error("Failed to update admin status:", err);
    }
  }

  /* ── Render ─────────────────────────────────────────── */

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
          <button
            className="btn btn-dark"
            type="button"
            onClick={() => signOut()}
          >
            <LogOut size={14} /> Sign Out
          </button>
        </div>
      </header>

      {/* Stats Grid */}
      <div className="stats-grid">
        <div className="stat-card stat-blue">
          <div className="stat-icon">
            <Users size={22} />
          </div>
          <div className="stat-content">
            <div className="stat-value">{stats.total}</div>
            <div className="stat-label">Total Users</div>
          </div>
        </div>
        <div className="stat-card stat-green">
          <div className="stat-icon">
            <UserCheck size={22} />
          </div>
          <div className="stat-content">
            <div className="stat-value">{stats.approved}</div>
            <div className="stat-label">Approved</div>
          </div>
        </div>
        <div className="stat-card stat-red">
          <div className="stat-icon">
            <UserX size={22} />
          </div>
          <div className="stat-content">
            <div className="stat-value">{stats.pending}</div>
            <div className="stat-label">Pending</div>
          </div>
        </div>
        <div className="stat-card stat-yellow">
          <div className="stat-icon">
            <Shield size={22} />
          </div>
          <div className="stat-content">
            <div className="stat-value">{stats.admins}</div>
            <div className="stat-label">Admins</div>
          </div>
        </div>
      </div>

      {/* User Management Table */}
      <section className="admin-section">
        <div className="queue-header">
          <span className="header-tag">Users</span>
          <h2>Manage Access</h2>
        </div>

        <div className="admin-table">
          <div className="admin-table-head">
            <span>User</span>
            <span>Email</span>
            <span>Joined</span>
            <span>Status</span>
            <span>Actions</span>
          </div>

          {users.map((u) => (
            <div className="admin-table-row" key={u._id}>
              <span className="cell-name">{u.name || "\u2014"}</span>
              <span className="cell-email">{u.email || "\u2014"}</span>
              <span className="cell-date">{formatDate(u._creationTime)}</span>
              <span className="cell-status">
                <span
                  className={`badge ${u.isApproved ? "completed" : "failed"}`}
                >
                  {u.isApproved ? "Approved" : "Pending"}
                </span>
                {u.isAdmin && (
                  <span className="badge running admin-badge">Admin</span>
                )}
              </span>
              <span className="cell-actions">
                {u.isApproved ? (
                  <button
                    className="action-btn action-danger"
                    onClick={() =>
                      handleApproval(u._id as Id<"users">, false)
                    }
                    title="Revoke download access"
                  >
                    <UserX size={14} />
                    <span>Revoke</span>
                  </button>
                ) : (
                  <button
                    className="action-btn action-success"
                    onClick={() =>
                      handleApproval(u._id as Id<"users">, true)
                    }
                    title="Approve download access"
                  >
                    <Check size={14} />
                    <span>Approve</span>
                  </button>
                )}
                <button
                  className={`action-btn ${u.isAdmin ? "action-danger" : "action-neutral"}`}
                  onClick={() =>
                    handleAdminToggle(u._id as Id<"users">, !u.isAdmin)
                  }
                  title={u.isAdmin ? "Remove admin role" : "Grant admin role"}
                >
                  {u.isAdmin ? <ShieldOff size={14} /> : <Shield size={14} />}
                </button>
              </span>
            </div>
          ))}

          {users.length === 0 && (
            <div className="admin-table-empty">
              <Users size={28} />
              <p>No users registered yet</p>
            </div>
          )}
        </div>
      </section>
    </main>
  );
}
