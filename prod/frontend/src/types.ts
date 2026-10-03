/* Shared frontend types.
   Fields the backend may omit or null out are optional/nullable so every
   consumer is forced through ?. / ?? (optional-chaining safe). The numeric
   usage counters stay required numbers: the backend always returns them and
   AdminDashboard/DownloaderPage do arithmetic on them. */

export type AppUser = {
  id: string;
  name?: string | null;
  email?: string | null;
  github_username?: string | null;
  is_admin: boolean;
  is_owner: boolean;
  usage_limit_bytes: number;
  ingress_bytes: number;
  egress_bytes: number;
  used_bytes: number;
  remaining_bytes: number;
  is_restricted_email: boolean;
  quota_notice?: string | null;
  created_at: string;
};

export type JobStatus = "queued" | "running" | "completed" | "failed" | "cancelled";

export type Job = {
  id: string;
  url: string;
  title?: string | null;
  thumbnail?: string | null;
  format: string;
  status: JobStatus;
  progress: number;
  message?: string | null;
  file_name?: string | null;
  download_url?: string | null;
  expires_at?: string | null;
  created_at: string;
};

export type HealthResponse = {
  status?: string | null;
  yt_dlp_version?: string | null;
};

/**
 * Typed API failure that preserves the HTTP status so callers can branch on
 * 401 (re-auth) / 429 (rate or quota limit) / 507 (storage full). A status of
 * 0 means the request never reached the server (network error).
 */
export class ApiError extends Error {
  status: number;
  detail: string;

  constructor(status: number, detail: string) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}
