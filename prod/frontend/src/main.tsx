import { ClerkProvider, useAuth } from "@clerk/react";
import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { AlertTriangle, Loader2 } from "lucide-react";
import { AdminDashboard } from "./AdminDashboard";
import { DownloaderPage } from "./DownloaderPage";
import { LoginPage } from "./LoginPage";
import type { AppUser } from "./types";
import "./styles.css";

const publishableKey = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY as string;
if (!publishableKey) throw new Error("Missing VITE_CLERK_PUBLISHABLE_KEY");

function AppContent() {
  const { isLoaded, isSignedIn, getToken } = useAuth();
  const [view, setView] = useState<"downloader" | "admin">("downloader");
  const [user, setUser] = useState<AppUser | null>(null);
  const [backendToken, setBackendToken] = useState("");
  const [profileError, setProfileError] = useState("");

  useEffect(() => {
    if (!isLoaded || !isSignedIn) {
      setUser(null);
      setBackendToken("");
      return;
    }
    let mounted = true;
    const loadProfile = async () => {
      try {
        const token = await getToken();
        if (!token) throw new Error("Clerk did not return a session token");
        const response = await fetch("/api/me", { headers: { Authorization: `Bearer ${token}` } });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.detail || "Could not load your account");
        if (mounted) {
          setBackendToken(token);
          setUser(body as AppUser);
          setProfileError("");
        }
      } catch (error) {
        if (mounted) setProfileError(error instanceof Error ? error.message : "Could not load your account");
      }
    };
    void loadProfile();
    const timer = window.setInterval(() => void loadProfile(), 45_000);
    return () => {
      mounted = false;
      window.clearInterval(timer);
    };
  }, [getToken, isLoaded, isSignedIn]);

  if (!isLoaded || (isSignedIn && !user && !profileError)) {
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

  if (!isSignedIn) return <LoginPage />;
  if (profileError) return (
    <main className="auth-shell">
      <section className="auth-card error-card" role="alert">
        <AlertTriangle size={36} />
        <h1>Account unavailable</h1>
        <p>{profileError}</p>
        <button className="btn btn-outline" type="button" onClick={() => window.location.reload()}>Retry</button>
      </section>
    </main>
  );
  if (!user) return null;

  if (view === "admin" && user.is_admin) {
    return <AdminDashboard user={user} token={backendToken} onBack={() => setView("downloader")} />;
  }

  return <DownloaderPage user={user} token={backendToken} onAdminClick={() => setView("admin")} />;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ClerkProvider
      publishableKey={publishableKey}
      afterSignOutUrl="/"
      appearance={{
        variables: {
          colorPrimary: "#1a56a0",
          colorBackground: "#fffbf0",
          colorForeground: "#1a1714",
          borderRadius: "0px",
          fontFamily: '"DM Sans", sans-serif',
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
  </React.StrictMode>,
);
