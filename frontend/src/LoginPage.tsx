import React, { useState, FormEvent } from "react";
import { useAuthActions } from "@convex-dev/auth/react";
import { Lock, Mail, KeyRound, Loader2, ArrowLeft } from "lucide-react";

type Step = "email" | "code";

export function LoginPage() {
  const { signIn } = useAuthActions();
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function handleEmailSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const formData = new FormData();
      formData.set("email", email);
      formData.set("flow", "email-verification-code");
      await signIn("resend-otp", formData);
      setStep("code");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send code");
    } finally {
      setLoading(false);
    }
  }

  async function handleCodeSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const formData = new FormData();
      formData.set("email", email);
      formData.set("code", code);
      formData.set("flow", "email-verification-code");
      await signIn("resend-otp", formData);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid or expired code");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-shell">
      <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-triangle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-line" aria-hidden="true" />

      <div className="auth-card slide-up">
        <div className="auth-corner" aria-hidden="true" />

        <div className="logo-row">
          <div className="logo-circle">
            <Lock size={20} />
          </div>
          <div>
            <span className="header-tag">Private node</span>
            <h1>Downloader</h1>
          </div>
        </div>

        {/* Step indicator */}
        <div className="auth-tabs">
          <div className={`auth-tab ${step === "email" ? "active" : ""}`} style={{ cursor: "default" }}>
            <Mail size={14} /> Email
          </div>
          <div className={`auth-tab ${step === "code" ? "active" : ""}`} style={{ cursor: "default" }}>
            <KeyRound size={14} /> Code
          </div>
        </div>

        {/* Step 1 — Email */}
        {step === "email" && (
          <form onSubmit={handleEmailSubmit}>
            <p className="auth-subtitle">Enter your email to receive a sign-in code.</p>
            <div className="field">
              <label className="field-label" htmlFor="auth-email">Email</label>
              <div className="input-box">
                <Mail size={16} />
                <input
                  id="auth-email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  type="email"
                  placeholder="you@example.com"
                  autoComplete="email"
                  required
                  autoFocus
                />
              </div>
            </div>

            {error && <p className="error-msg fade-in" role="alert">{error}</p>}

            <button className="btn btn-primary" type="submit" disabled={loading || !email}>
              {loading ? <Loader2 className="spin" size={16} /> : <Mail size={16} />}
              Send Code
            </button>
          </form>
        )}

        {/* Step 2 — Code */}
        {step === "code" && (
          <form onSubmit={handleCodeSubmit}>
            <p className="auth-subtitle">
              Code sent to <strong>{email}</strong>. Check your inbox.
            </p>
            <div className="field">
              <label className="field-label" htmlFor="auth-code">One-time code</label>
              <div className="input-box">
                <KeyRound size={16} />
                <input
                  id="auth-code"
                  value={code}
                  onChange={e => setCode(e.target.value.replace(/\D/g, "").slice(0, 8))}
                  type="text"
                  inputMode="numeric"
                  placeholder="123456"
                  autoComplete="one-time-code"
                  required
                  autoFocus
                />
              </div>
            </div>

            {error && <p className="error-msg fade-in" role="alert">{error}</p>}

            <button className="btn btn-primary" type="submit" disabled={loading || code.length < 6}>
              {loading ? <Loader2 className="spin" size={16} /> : <KeyRound size={16} />}
              Verify
            </button>

            <button
              type="button"
              className="btn btn-dark"
              onClick={() => { setStep("email"); setCode(""); setError(""); }}
              style={{ marginTop: 8 }}
            >
              <ArrowLeft size={14} /> Different email
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
