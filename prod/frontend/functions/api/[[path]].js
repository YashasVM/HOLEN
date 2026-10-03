// Optional Cloudflare Pages API proxy. The upstream validates every token/ticket.
const UPSTREAM_HOST = "holen.yvmx.dpdns.org";

const ALLOWED_METHODS = new Set(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]);

// RFC 9110 §7.6 hop-by-hop headers + Host/Content-Length (fetch sets those).
const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "proxy-connection",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
]);

function json(status, data) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export async function onRequest(context) {
  const { request } = context;
  const method = request.method.toUpperCase();

  if (!ALLOWED_METHODS.has(method)) {
    return json(405, { detail: `Method ${method} not allowed` });
  }

  const sourceUrl = new URL(request.url);
  const fileRequest = ["GET", "HEAD"].includes(method)
    && /^\/api\/jobs\/[^/]+\/download$/.test(sourceUrl.pathname);
  const hasTicket = /(?:^|;\s*)holen_download_ticket=[^;]+/.test(request.headers.get("cookie") || "");
  const healthRequest = ["GET", "HEAD"].includes(method) && sourceUrl.pathname === "/api/health";
  if (!request.headers.get("authorization") && !(fileRequest && hasTicket) && !healthRequest) {
    return json(401, { detail: "Missing Authorization header" });
  }

  // Set a distinct tunnel origin when serving the frontend on Pages.
  const origin = new URL(context.env?.API_UPSTREAM_ORIGIN || `https://${UPSTREAM_HOST}`);
  if (origin.protocol !== "https:" || origin.host === sourceUrl.host) {
    return json(503, { detail: "Configure a distinct HTTPS API_UPSTREAM_ORIGIN" });
  }
  const url = new URL(sourceUrl.pathname + sourceUrl.search, origin);

  const headers = new Headers();
  for (const [name, value] of request.headers) {
    if (!HOP_BY_HOP.has(name.toLowerCase())) headers.set(name, value);
  }

  const controller = new AbortController();
  // Limit time to response headers, never the lifetime of a file stream.
  const timeout = setTimeout(() => controller.abort(), 100_000);
  const init = {
    method,
    headers,
    redirect: "manual",
    signal: controller.signal,
  };
  if (method !== "GET" && method !== "HEAD" && request.body != null) {
    init.body = request.body;
    // Required by the fetch spec when forwarding a stream body.
    init.duplex = "half";
  }

  try {
    return await fetch(new Request(url, init));
  } catch (error) {
    return json(502, {
      detail: "Upstream request failed",
      error: error instanceof Error ? error.message : String(error),
    });
  } finally {
    clearTimeout(timeout);
  }
}
