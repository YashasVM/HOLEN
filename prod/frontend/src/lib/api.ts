import { ApiError } from "../types";

type TokenGetter = (options?: { skipCache?: boolean }) => Promise<string | null>;

/** Retry authentication once; never replay a mutation after a network failure. */
export async function requestJson<T>(path: string, getToken: TokenGetter, init?: RequestInit): Promise<T> {
  const signal = init?.signal
    ? AbortSignal.any([init.signal, AbortSignal.timeout(120_000)])
    : AbortSignal.timeout(120_000);
  const request = async (fresh = false) => {
    const token = await getToken({ skipCache: fresh });
    if (!token) throw new ApiError(401, "Session expired");
    const headers = new Headers(init?.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init?.body != null && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    return fetch(path, { ...init, signal, headers });
  };
  let response: Response;
  try {
    response = await request();
    if (response.status === 401) response = await request(true);
  } catch (error) {
    if (error instanceof ApiError || (error instanceof DOMException && error.name === "AbortError" && init?.signal?.aborted)) throw error;
    throw new ApiError(0, error instanceof DOMException && error.name === "TimeoutError"
      ? "The server took too long to respond. Try again."
      : "Connection interrupted. Check your connection and try again.");
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, typeof body?.detail === "string"
    ? body.detail : `Server unavailable (${response.status}). Try again shortly.`);
  if (body === null && response.status !== 204) throw new ApiError(0, "The server returned an incomplete response. Try again.");
  return body as T;
}
