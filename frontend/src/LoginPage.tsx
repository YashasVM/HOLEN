import React from "react";
import { SignInButton, SignUpButton } from "@clerk/react";
import { ArrowRight, HardDrive, ShieldCheck, UserPlus } from "lucide-react";

export function LoginPage() {
  return (
    <main className="entry-shell">
      <div className="entry-frame slide-up">
        <section className="entry-copy" aria-labelledby="auth-title">
          <div className="entry-brand">
            <span className="entry-mark">H</span>
            <span>Holen</span>
          </div>
          <div className="entry-message">
            <p className="entry-kicker">Private video downloader</p>
            <h1 id="auth-title"><span>Save it.</span><em>Keep it.</em></h1>
            <p className="entry-lede">Keep YouTube video and audio downloads in one private queue, ready when you are.</p>
          </div>
          <div className="entry-facts" aria-label="Service details">
            <div><HardDrive size={18} /><span><strong>5 GB</strong><small>Included with every account</small></span></div>
            <div><ShieldCheck size={18} /><span><strong>Private queue</strong><small>Your downloads stay with your account</small></span></div>
          </div>
        </section>

        <section className="entry-access" aria-label="Account access">
          <span className="header-tag">Your space</span>
          <h2>Continue.</h2>
          <p>Sign in to continue where you left off, or create an account to get started.</p>
          <div className="entry-actions">
            <SignInButton mode="modal">
              <button className="entry-primary" type="button"><ArrowRight size={18} /> Sign in</button>
            </SignInButton>
            <SignUpButton mode="modal">
              <button className="entry-secondary" type="button"><UserPlus size={17} /> Create account</button>
            </SignUpButton>
          </div>
        </section>
      </div>
    </main>
  );
}
