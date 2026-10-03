import { ClerkProvider, useAuth } from "@clerk/react";
import React, { lazy, Suspense, useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertTriangle, Loader2 } from "lucide-react";
const AdminDashboard = lazy(() => import("./AdminDashboard").then((module) => ({ default: module.AdminDashboard })));
import { DownloaderPage } from "./DownloaderPage";
import { LoginPage } from "./LoginPage";
import type { AppUser } from "./types";
import { requestJson } from "./lib/api";
import "./styles.css";

// Never throw at module top: a missing key would blank the whole app with no
// UI at all. Render a fallback card instead (see MissingKeyCard / Root).
const publishableKey = (import.meta.env.VITE_CLERK_PUBLISHABLE_KEY as string | undefined) ?? "";

/* ── ErrorBoundary ─────────────────────────────────────────── */

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error): { error: Error } {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    console.error("UI crashed:", error, info);
  }

  render(): React.ReactNode {
    if (this.state.error) {
      return (
        <main className="auth-shell">
          <section className="auth-card error-card" role="alert">
            <AlertTriangle size={36} />
            <h1>Something broke</h1>
            <p>{this.state.error.message || "The app hit an unexpected error."}</p>
            <button className="btn btn-outline" type="button" onClick={() => window.location.reload()}>
              Reload
            </button>
          </section>
        </main>
      );
    }
    return this.props.children;
  }
}

function MissingKeyCard() {
  return (
    <main className="auth-shell">
      <section className="auth-card error-card" role="alert">
        <AlertTriangle size={36} />
        <h1>Sign-in unavailable</h1>
        <p>Missing VITE_CLERK_PUBLISHABLE_KEY. Set it in the frontend environment and rebuild.</p>
      </section>
    </main>
  );
}

/* ── BootScreen ────────────────────────────────────────────── */

function BootScreen() {
  return (
    <main className="boot-screen" aria-busy="true" aria-live="polite">
      <section className="boot-panel">
        <div className="boot-mark" aria-hidden="true">H</div>
        <div className="boot-copy">
          <span className="boot-kicker">Holen</span>
          <h1>Getting ready</h1>
          <p className="boot-eta"><Loader2 className="spin" size={18} aria-hidden="true" /> Connecting your account…</p>
          <p>The server sleeps when it is idle. Your account will open as soon as it is ready.</p>
        </div>
      </section>
    </main>
  );
}

/* ── App ───────────────────────────────────────────────────── */

function AppContent() {
  const { isLoaded, isSignedIn, getToken } = useAuth();
  const [view, setView] = useState<"downloader" | "admin">("downloader");
  const [user, setUser] = useState<AppUser | null>(null);
  const [profileError, setProfileError] = useState("");

  const loadProfile = useCallback(() => requestJson<AppUser>("/api/me", getToken), [getToken]);

  useEffect(() => {
    if (!isLoaded || !isSignedIn) {
      setUser(null);
      setProfileError("");
      return;
    }
    let active = true;
    let inflight = false;
    const syncProfile = async () => {
      if (inflight) return;
      inflight = true;
      try {
        const profile = await loadProfile();
        if (!active) return;
        setUser(profile);
        setProfileError("");
      } catch (error) {
        if (!active) return;
        setProfileError(error instanceof Error ? error.message : "Could not load your account");
      } finally {
        inflight = false;
      }
    };
    void syncProfile();
    const onVisible = () => {
      if (!document.hidden) void syncProfile();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    return () => {
      active = false;
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
    };
  }, [isLoaded, isSignedIn, loadProfile]);

  if (!isLoaded) return <BootScreen />;

  if (!isSignedIn) return <LoginPage />;
  if (profileError && !user) return (
    <main className="auth-shell">
      <section className="auth-card error-card" role="alert">
        <AlertTriangle size={36} />
        <h1>Account unavailable</h1>
        <p>{profileError}</p>
        <button className="btn btn-outline" type="button" onClick={() => window.location.reload()}>Retry</button>
      </section>
    </main>
  );
  if (!user) return <BootScreen />;
  if (view === "admin" && user.is_admin) {
    return <Suspense fallback={<BootScreen />}><AdminDashboard user={user} onBack={() => setView("downloader")} /></Suspense>;
  }

  return <div className="app-enter"><DownloaderPage user={user} onAdminClick={() => setView("admin")} /></div>;
}

function Root() {
  if (!publishableKey) return <MissingKeyCard />;
  return (
    <ClerkProvider
      publishableKey={publishableKey}
      afterSignOutUrl="/"
      appearance={{
        variables: {
          colorPrimary: "#1a56a0",
          colorBackground: "#fffbf0",
          colorForeground: "#1a1714",
          borderRadius: "0px",
          fontFamily: 'system-ui, sans-serif',
        },
        elements: {
          cardBox: "clerk-card-box",
          card: "clerk-card",
          formButtonPrimary: "clerk-primary-button",
          formFieldInput: "clerk-input",
          footerActionLink: "clerk-link",
          socialButtonsBlockButton: "clerk-social-button",
          userButtonPopoverCard: "clerk-popover",
          userButtonPopoverActionButton: "clerk-popover-action",
        },
      }}
    >
      <AppContent />
    </ClerkProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <Root />
    </ErrorBoundary>
  </React.StrictMode>,
);
