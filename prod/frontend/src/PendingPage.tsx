import React from "react";
import { useClerk } from "@clerk/react";
import { Clock, LogOut } from "lucide-react";

interface PendingPageProps {
  userName?: string;
}

export function PendingPage({ userName }: PendingPageProps) {
  const { signOut } = useClerk();

  return (
    <main className="auth-shell">
      {/* Decorative Bauhaus geometric elements */}
      <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-triangle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-line" aria-hidden="true" />

      <div className="auth-card pending-card slide-up">
        <div className="auth-corner" aria-hidden="true" />

        <div className="pending-icon pulse">
          <Clock size={36} />
        </div>

        <h1 className="pending-title">Pending Approval</h1>

        <p className="pending-text">
          {userName ? `Hey ${userName}, your` : "Your"} account is awaiting
          admin approval. You&rsquo;ll be able to use the downloader once an
          administrator approves your access.
        </p>

        {/* Bauhaus stripe decoration */}
        <div className="pending-stripes" aria-hidden="true">
          <div className="stripe stripe-red" />
          <div className="stripe stripe-blue" />
          <div className="stripe stripe-yellow" />
        </div>

        <button
          className="btn btn-dark"
          type="button"
          onClick={() => void signOut()}
        >
          <LogOut size={14} /> Sign Out
        </button>
      </div>
    </main>
  );
}
