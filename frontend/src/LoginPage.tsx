import React, { useState, FormEvent } from "react";
import { useAuthActions } from "@convex-dev/auth/react";
import {
  Lock,
  Mail,
  KeyRound,
  UserPlus,
  LogIn,
  Loader2,
  User,
} from "lucide-react";

export function LoginPage() {
  const { signIn } = useAuthActions();
  const [mode, setMode] = useState<"signIn" | "signUp">("signIn");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const formData = new FormData();
      formData.set("email", email);
      formData.set("password", password);
      formData.set("flow", mode);
      if (mode === "signUp" && name) {
        formData.set("name", name);
      }
      await signIn("password", formData);
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : "Authentication failed";
      setError(
        message.includes("Invalid") || message.includes("Could not")
          ? "Invalid email or password"
          : message,
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-shell">
      {/* Decorative Bauhaus geometric elements */}
      <div className="bauhaus-deco bauhaus-circle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-rect" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-triangle" aria-hidden="true" />
      <div className="bauhaus-deco bauhaus-line" aria-hidden="true" />

      <form className="auth-card slide-up" onSubmit={handleSubmit}>
        {/* Bauhaus corner mark */}
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

        {/* Sign In / Sign Up tabs */}
        <div className="auth-tabs">
          <button
            type="button"
            className={`auth-tab ${mode === "signIn" ? "active" : ""}`}
            onClick={() => {
              setMode("signIn");
              setError("");
            }}
          >
            <LogIn size={14} /> Sign In
          </button>
          <button
            type="button"
            className={`auth-tab ${mode === "signUp" ? "active" : ""}`}
            onClick={() => {
              setMode("signUp");
              setError("");
            }}
          >
            <UserPlus size={14} /> Sign Up
          </button>
        </div>

        {/* Name field (sign up only) */}
        {mode === "signUp" && (
          <div className="field fade-in">
            <label className="field-label" htmlFor="auth-name">
              Name
            </label>
            <div className="input-box">
              <User size={16} />
              <input
                id="auth-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Your name"
                autoComplete="name"
              />
            </div>
          </div>
        )}

        {/* Email field */}
        <div className="field">
          <label className="field-label" htmlFor="auth-email">
            Email
          </label>
          <div className="input-box">
            <Mail size={16} />
            <input
              id="auth-email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              type="email"
              placeholder="you@example.com"
              autoComplete="email"
              required
            />
          </div>
        </div>

        {/* Password field */}
        <div className="field">
          <label className="field-label" htmlFor="auth-pw">
            Password
          </label>
          <div className="input-box">
            <KeyRound size={16} />
            <input
              id="auth-pw"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              type="password"
              placeholder="••••••••"
              autoComplete={
                mode === "signUp" ? "new-password" : "current-password"
              }
              required
              minLength={8}
            />
          </div>
        </div>

        {/* Error message */}
        {error && (
          <p className="error-msg fade-in" role="alert">
            {error}
          </p>
        )}

        {/* Submit button */}
        <button
          className="btn btn-primary"
          type="submit"
          disabled={loading || !email || !password}
        >
          {loading ? (
            <Loader2 className="spin" size={16} />
          ) : mode === "signIn" ? (
            <LogIn size={16} />
          ) : (
            <UserPlus size={16} />
          )}
          {mode === "signIn" ? "Sign In" : "Create Account"}
        </button>

        {/* Toggle link */}
        <p className="auth-footer">
          {mode === "signIn"
            ? "Don\u2019t have an account? "
            : "Already have an account? "}
          <button
            type="button"
            className="link-btn"
            onClick={() => {
              setMode(mode === "signIn" ? "signUp" : "signIn");
              setError("");
            }}
          >
            {mode === "signIn" ? "Sign up" : "Sign in"}
          </button>
        </p>
      </form>
    </main>
  );
}
