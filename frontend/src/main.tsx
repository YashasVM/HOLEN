import React, { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { Check, Download, Film, Link2, Loader2, Music2, Trash2, X } from "lucide-react";
import "./styles.css";

type Format = "best" | "1080p" | "720p" | "audio" | "mp3";

type Job = {
  id: string;
  url: string;
  title?: string;
  thumbnail?: string;
  format: Format;
  status: "queued" | "running" | "completed" | "failed" | "cancelled";
  progress: number;
  message?: string;
  file_name?: string;
  download_url?: string;
  created_at: string;
};

const FORMATS: Array<{ value: Format; label: string; description: string }> = [
  { value: "best", label: "Best video", description: "Best available MP4" },
  { value: "1080p", label: "1080p", description: "HD MP4" },
  { value: "720p", label: "720p", description: "Smaller MP4" },
  { value: "audio", label: "Audio", description: "Best M4A" },
  { value: "mp3", label: "MP3", description: "Portable audio" },
];

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date(value));
}

function jobIcon(format: Format) {
  return format === "audio" || format === "mp3" ? <Music2 size={20} /> : <Film size={20} />;
}

function readableError(error: unknown) {
  if (!(error instanceof Error)) return "Something went wrong. Please try again.";
  if (error.message.includes("Too many requests")) return "Too many requests from this network. Please wait and try again.";
  return error.message || "Something went wrong. Please try again.";
}

function App() {
  const [url, setUrl] = useState("");
  const [format, setFormat] = useState<Format>("best");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const refreshJobs = useCallback(async () => {
    const response = await fetch("/api/jobs");
    if (!response.ok) throw new Error("Could not refresh the download queue.");
    setJobs(await response.json() as Job[]);
  }, []);

  useEffect(() => {
    void refreshJobs().catch(() => undefined);
    const interval = window.setInterval(() => void refreshJobs().catch(() => undefined), 2000);
    return () => window.clearInterval(interval);
  }, [refreshJobs]);

  const activeCount = useMemo(
    () => jobs.filter((job) => job.status === "queued" || job.status === "running").length,
    [jobs],
  );

  async function queueDownload(event: FormEvent) {
    event.preventDefault();
    if (!url.trim()) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch("/api/jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url: url.trim(), format }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail || "Could not queue the download.");
      setUrl("");
      setNotice("Download added to the queue.");
      await refreshJobs();
    } catch (requestError) {
      setError(readableError(requestError));
    } finally {
      setBusy(false);
    }
  }

  async function removeJob(job: Job) {
    setError("");
    try {
      const response = await fetch(`/api/jobs/${job.id}`, { method: "DELETE" });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.detail || "Could not update this download.");
      setNotice(job.status === "queued" || job.status === "running" ? "Download cancelled." : "Download removed.");
      await refreshJobs();
    } catch (requestError) {
      setError(readableError(requestError));
    }
  }

  return (
    <main className="page-shell">
      <header className="site-header">
        <a className="brand" href="/" aria-label="Holen home"><span>H</span> Holen</a>
        <p>Paste. Pick. Save.</p>
      </header>

      <section className="hero" aria-labelledby="page-title">
        <div className="hero-copy">
          <span className="eyebrow">Self-hosted video downloader</span>
          <h1 id="page-title">Get the file,<br /><em>without the friction.</em></h1>
          <p>Paste a YouTube video or Short. Choose a format. The file is ready here when the queue finishes.</p>
        </div>

        <form className="download-form" onSubmit={queueDownload}>
          <label htmlFor="video-url">YouTube URL</label>
          <div className="url-field">
            <Link2 size={20} aria-hidden="true" />
            <input
              id="video-url"
              type="url"
              value={url}
              onChange={(event) => { setUrl(event.target.value); setError(""); }}
              placeholder="https://youtube.com/watch?v=…"
              autoComplete="off"
              spellCheck={false}
              required
            />
            {url ? <button className="clear-url" type="button" onClick={() => setUrl("")} aria-label="Clear URL"><X size={16} /></button> : null}
          </div>

          <fieldset>
            <legend>Format</legend>
            <div className="format-grid">
              {FORMATS.map((option) => (
                <label className={`format-option ${format === option.value ? "selected" : ""}`} key={option.value}>
                  <input type="radio" name="format" value={option.value} checked={format === option.value} onChange={() => setFormat(option.value)} />
                  <strong>{option.label}</strong><small>{option.description}</small>
                </label>
              ))}
            </div>
          </fieldset>

          <button className="queue-button" type="submit" disabled={busy}>
            {busy ? <Loader2 className="spin" size={19} /> : <Download size={19} />} {busy ? "Checking video…" : "Queue download"}
          </button>
          <p className="form-note">Only download media you have the rights to save.</p>
          {notice ? <p className="notice success" role="status"><Check size={16} /> {notice}</p> : null}
          {error ? <p className="notice failure" role="alert">{error}</p> : null}
        </form>
      </section>

      <section className="queue" aria-labelledby="queue-heading">
        <div className="section-heading">
          <div><span className="eyebrow">Live queue</span><h2 id="queue-heading">Your downloads</h2></div>
          {activeCount ? <span className="queue-count">{activeCount} active</span> : null}
        </div>

        <div className="job-list">
          {jobs.map((job) => {
            const active = job.status === "queued" || job.status === "running";
            const ready = job.status === "completed" && job.download_url;
            const progress = Math.max(0, Math.min(100, job.progress));
            return (
              <article className="job" key={job.id}>
                {job.thumbnail ? <img src={job.thumbnail} alt="" loading="lazy" /> : <div className="job-icon">{jobIcon(job.format)}</div>}
                <div className="job-details">
                  <div className="job-title-row"><span className={`status ${job.status}`}>{job.status}</span><time>{formatDate(job.created_at)}</time></div>
                  <h3 title={job.title || job.url}>{job.title || job.file_name || job.url}</h3>
                  <p>{active ? job.message || "Waiting…" : job.message || job.format}</p>
                  {active ? <div className="progress" aria-label={`${Math.round(progress)}% complete`}><i style={{ transform: `scaleX(${progress / 100})` }} /></div> : null}
                </div>
                <div className="job-actions">
                  {ready ? <a className="save-button" href={job.download_url} download={job.file_name}><Download size={16} /> Save file</a> : null}
                  <button className="remove-button" type="button" onClick={() => void removeJob(job)} aria-label={active ? "Cancel download" : "Remove download"} title={active ? "Cancel" : "Remove"}><Trash2 size={17} /></button>
                </div>
              </article>
            );
          })}
          {!jobs.length ? <div className="empty"><Download size={26} /><p>Your queue is clear. Paste a link above to begin.</p></div> : null}
        </div>
      </section>

      <footer>Holen is self-hosted. Keep it private or add access controls before sharing it publicly.</footer>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
