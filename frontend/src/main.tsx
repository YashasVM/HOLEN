import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  ConvexReactClient,
  useConvexAuth,
  useQuery,
  useMutation,
} from "convex/react";
import { ConvexAuthProvider } from "@convex-dev/auth/react";
import { api } from "../convex/_generated/api";
import { Loader2 } from "lucide-react";
import { LoginPage } from "./LoginPage";
import { PendingPage } from "./PendingPage";
import { DownloaderPage } from "./DownloaderPage";
import { AdminDashboard } from "./AdminDashboard";
import "./styles.css";

/* ── Convex Client ───────────────────────────────────────── */

const convex = new ConvexReactClient(
  import.meta.env.VITE_CONVEX_URL as string,
);

/* ── App Content (inside auth provider) ──────────────────── */

function AppContent() {
  const { isAuthenticated, isLoading } = useConvexAuth();
  const user = useQuery(
    api.users.currentUser,
    isAuthenticated ? undefined : "skip",
  );
  const setupUser = useMutation(api.users.setupNewUser);
  const [view, setView] = useState<"downloader" | "admin">("downloader");
  const [setupDone, setSetupDone] = useState(false);

  /* Initialize new user profile on first auth */
  useEffect(() => {
    if (isAuthenticated && user && !setupDone) {
      setupUser()
        .then(() => setSetupDone(true))
        .catch(() => setSetupDone(true));
    }
  }, [isAuthenticated, user, setupDone, setupUser]);

  /* ── Loading state ─────────────────────────────────── */

  if (isLoading) {
    return (
      <main className="auth-shell">
        <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
        <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
        <div className="loading-state slide-up">
          <Loader2 className="spin" size={36} />
          <p>Loading</p>
        </div>
      </main>
    );
  }

  /* ── Not authenticated ─────────────────────────────── */

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  /* ── Waiting for user data ─────────────────────────── */

  if (user === undefined) {
    return (
      <main className="auth-shell">
        <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
        <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
        <div className="loading-state slide-up">
          <Loader2 className="spin" size={36} />
          <p>Loading profile</p>
        </div>
      </main>
    );
  }

  /* ── Not approved ──────────────────────────────────── */

  if (user && !user.isApproved) {
    return <PendingPage userName={user.name} />;
  }

  /* ── Admin dashboard ───────────────────────────────── */

  if (view === "admin" && user?.isAdmin) {
    return (
      <AdminDashboard
        user={{
          name: user.name,
          email: user.email,
          isAdmin: user.isAdmin,
        }}
        onBack={() => setView("downloader")}
      />
    );
  }

  /* ── Main downloader ───────────────────────────────── */

  return (
    <DownloaderPage
      user={{
        name: user?.name,
        email: user?.email,
        isAdmin: user?.isAdmin ?? false,
      }}
      onAdminClick={() => setView("admin")}
    />
  );
}

/* ── App Root ────────────────────────────────────────────── */

function App() {
  return (
    <ConvexAuthProvider client={convex}>
      <AppContent />
    </ConvexAuthProvider>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
