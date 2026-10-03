#!/usr/bin/env python3
"""Wake Holen on demand and release its containers after idle time."""
import argparse
import http.client
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "trailers", "transfer-encoding", "upgrade"}

# No bundle, web fonts, or estimated countdown needed before the app starts.
LOADING_PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#f5efe0"><title>Starting Holen</title><style>
*{box-sizing:border-box}body{margin:0;min-height:100dvh;display:grid;place-items:center;padding:32px 20px;background:#f5efe0;color:#1a1714;font:16px/1.6 system-ui,sans-serif}main{width:min(100%,560px);min-width:0}.brand{display:flex;align-items:center;gap:14px;font-weight:800;letter-spacing:.1em}.mark{display:grid;place-items:center;width:44px;height:44px;background:#d42b20;color:white;box-shadow:4px 4px #1a1714;letter-spacing:0}h1{font-size:clamp(2rem,7vw,3.5rem);line-height:1.15;letter-spacing:-.03em;margin:40px 0 16px;overflow-wrap:break-word}p{color:#5e574b;margin:0 0 24px}.activity{display:flex;align-items:center;gap:12px;border-top:2px solid #1a1714;padding:20px 0}.dot{flex-shrink:0;width:10px;height:10px;border-radius:50%;background:#1a56a0;animation:pulse 1.5s ease-in-out infinite}#status{margin:0;font-size:.9rem}.note{font-size:.85rem}button{font:inherit;font-weight:700;background:#1a56a0;color:white;border:2px solid #1a1714;padding:10px 20px;cursor:pointer}button:focus-visible{outline:3px solid #d42b20;outline-offset:4px}@keyframes pulse{50%{opacity:.35}}@media(prefers-reduced-motion:reduce){.dot{animation:none}}
</style></head><body><main><div class="brand"><span class="mark" aria-hidden="true">H</span>HOLEN</div><h1 id="title">Getting your downloads ready.</h1><p>The server rests when it is idle to save power. This page will open automatically when it is ready.</p><div class="activity" role="status"><span class="dot" aria-hidden="true"></span><p id="status">Starting Holen…</p></div><p class="note">You can leave this tab open. Your files and account stay saved.</p><button id="retry" hidden onclick="location.reload()">Try again</button><noscript><p>JavaScript is disabled. Refresh this page in a moment to continue.</p></noscript></main><script>
const statusText=document.getElementById('status'),retry=document.getElementById('retry');let attempts=0;async function ready(){try{const r=await fetch('/__holen_status',{cache:'no-store',signal:AbortSignal.timeout(5000)});const s=await r.json();if(s.ready){location.reload();return}if(s.error){document.getElementById('title').textContent='Holen could not start.';statusText.textContent='Try again in a moment. If this continues, the host needs to check the server.';retry.hidden=false;return}statusText.textContent=++attempts>15?'Still starting. This can take a little longer after a restart.':'Starting Holen…'}catch(_){statusText.textContent='Reconnecting to the server…';retry.hidden=false}setTimeout(ready,2000)}ready();
</script></body></html>""".encode()


class App:
    def __init__(self, compose, idle_seconds, startup_seconds):
        self.command = ["docker", "compose", "--env-file", ".env"]
        for path in ([compose] if isinstance(compose, str) else compose):
            self.command.extend(["-f", path])
        self.idle_seconds = idle_seconds
        self.startup_seconds = startup_seconds
        self.lock = threading.Lock()
        self.startup_lock = threading.Lock()
        self.starting = False
        self.error = None
        self.active_requests = 0
        self.last_activity = time.monotonic()
        self.ready_until = 0
        self.ready_generation = 0
        self.stopping = False

    def health(self):
        connection = http.client.HTTPConnection("127.0.0.1", 8088, timeout=2)
        try:
            connection.request("GET", "/api/health")
            response = connection.getresponse()
            data = json.loads(response.read(64 * 1024))
            return data if response.status == 200 and data.get("status") == "ok" else None
        except (OSError, http.client.HTTPException, ValueError):
            return None
        finally:
            connection.close()

    def invalidate_readiness(self):
        with self.lock:
            self.ready_generation += 1
            self.ready_until = 0

    def running(self):
        # ponytail: three-second cache; generations discard probes across a stop.
        with self.lock:
            if self.stopping:
                return False
            if time.monotonic() < self.ready_until:
                return True
            generation = self.ready_generation
        healthy = self.health() is not None
        with self.lock:
            if self.stopping or generation != self.ready_generation:
                return False
            if healthy:
                self.ready_until = time.monotonic() + 3
            return healthy

    def ensure_running(self):
        if self.running():
            return True
        with self.startup_lock:
            with self.lock:
                if self.error:
                    return False
            if self.running():
                return True
            deadline = time.monotonic() + self.startup_seconds
            try:
                subprocess.run(self.command + ["up", "-d", "--remove-orphans"], check=True, timeout=self.startup_seconds)
                while time.monotonic() < deadline:
                    if self.running():
                        with self.lock:
                            self.error = None
                        return True
                    time.sleep(.5)
            except (subprocess.SubprocessError, OSError) as exc:
                print(f"Holen startup failed: {exc}", flush=True)
            with self.lock:
                self.error = "startup_failed"
        return False

    def begin_start(self, retry=False):
        if self.running():
            return
        with self.lock:
            if self.starting or (self.error and not retry):
                return
            self.starting = True
            self.error = None
            self.last_activity = time.monotonic()
        def start():
            try:
                self.ensure_running()
            finally:
                with self.lock:
                    self.starting = False
                    self.last_activity = time.monotonic()
        threading.Thread(target=start, daemon=True).start()

    def stop_if_idle(self):
        # Starting and stopping share a lock, so a request never races compose.
        with self.startup_lock:
            with self.lock:
                if self.starting or self.active_requests or time.monotonic() - self.last_activity < self.idle_seconds:
                    return False
            health = self.health()
            # Fail closed: never interrupt jobs when their state cannot be read.
            if health is None or health.get("active_jobs", 1) != 0:
                return False
            with self.lock:
                if self.starting or self.active_requests or time.monotonic() - self.last_activity < self.idle_seconds:
                    return False
                self.stopping = True
                self.ready_generation += 1
                self.ready_until = 0
            try:
                subprocess.run(self.command + ["stop"], check=True, timeout=60)
            except (subprocess.SubprocessError, OSError) as exc:
                print(f"Holen stop failed: {exc}", flush=True)
                return False
            finally:
                with self.lock:
                    self.ready_generation += 1
                    self.ready_until = 0
                    self.stopping = False
            return True

    def stop_when_idle(self):
        while True:
            time.sleep(30)
            self.stop_if_idle()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 30  # Release idle keep-alive threads and sockets.
    app = None

    def log_message(self, *_):
        pass

    def do_POST(self): self.proxy()
    def do_PUT(self): self.proxy()
    def do_PATCH(self): self.proxy()
    def do_DELETE(self): self.proxy()
    def do_OPTIONS(self): self.proxy()
    def do_HEAD(self): self.proxy()

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/__holen_status":
            self.status()
        elif "text/html" in self.headers.get("Accept", "") and not self.app.running():
            self.app.begin_start(retry=True)
            self.reply(200, "text/html; charset=utf-8", LOADING_PAGE)
        else:
            self.proxy()

    def reply(self, status, content_type, payload):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def status(self):
        self.app.begin_start()
        self.reply(200, "application/json", json.dumps({"ready": self.app.running(), "error": self.app.error}).encode())

    def proxy(self):
        app = self.app
        with app.lock:
            app.active_requests += 1
            app.last_activity = time.monotonic()
        connection = None
        sent_headers = False
        try:
            if self.headers.get("Transfer-Encoding"):
                self.close_connection = True
                self.send_error(501, "Chunked request bodies are not supported")
                return
            try:
                lengths = self.headers.get_all("Content-Length", [])
                length = int(lengths[0]) if lengths else 0
                if len(lengths) > 1 or length < 0 or length > 1024 * 1024:
                    raise ValueError
            except ValueError:
                self.close_connection = True
                self.send_error(400, "Invalid request body length")
                return
            if not app.ensure_running():
                self.close_connection = True
                self.send_error(503, "Holen could not start; retry in a moment")
                return
            self.connection.settimeout(120)
            body = self.rfile.read(length)
            if len(body) != length:
                self.close_connection = True
                self.send_error(400, "Incomplete request body")
                return
            blocked = HOP_BY_HOP | {token.strip().lower() for token in self.headers.get("Connection", "").split(",")}
            headers = {key: value for key, value in self.headers.items() if key.lower() not in blocked}
            headers["X-Forwarded-For"] = self.client_address[0]
            connection = http.client.HTTPConnection("127.0.0.1", 8088, timeout=10)
            connection.request(self.command, self.path, body=body, headers=headers)
            connection.sock.settimeout(120)
            response = connection.getresponse()
            self.send_response(response.status, response.reason)
            blocked = HOP_BY_HOP | {token.strip().lower() for token in response.getheader("Connection", "").split(",")}
            for key, value in response.getheaders():
                if key.lower() not in blocked:
                    self.send_header(key, value)
            if response.getheader("Content-Length") is None and self.command != "HEAD" and response.status not in (204, 304):
                self.send_header("Connection", "close")
                self.close_connection = True
            self.end_headers()
            sent_headers = True
            if self.command != "HEAD":
                # read1 forwards available bytes immediately, including SSE heartbeats.
                while chunk := response.read1(64 * 1024):
                    self.wfile.write(chunk)
                if response.length not in (None, 0):
                    self.close_connection = True  # Truncated file: close so clients can detect/retry.
        except (OSError, http.client.HTTPException) as exc:
            app.invalidate_readiness()
            self.close_connection = True
            if not sent_headers and not isinstance(exc, (BrokenPipeError, ConnectionResetError)):
                self.send_error(502, "Holen connection failed; retry in a moment")
        finally:
            if connection is not None:
                connection.close()
            try:
                self.connection.settimeout(self.timeout)
            except OSError:
                pass
            with app.lock:
                app.active_requests -= 1
                app.last_activity = time.monotonic()


class BoundedHTTPServer(ThreadingHTTPServer):
    def __init__(self, address, handler, max_connections=32):
        super().__init__(address, handler)
        self.slots = threading.BoundedSemaphore(max_connections)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(1)
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\nRetry-After: 2\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose", action="append")
    parser.add_argument("--idle-seconds", type=int, default=900)
    parser.add_argument("--startup-seconds", type=int, default=90)
    args = parser.parse_args()
    if min(args.idle_seconds, args.startup_seconds) <= 0:
        parser.error("timeouts must be positive")
    Handler.app = App(args.compose or ["prod/docker-compose.yml"], args.idle_seconds, args.startup_seconds)
    threading.Thread(target=Handler.app.stop_when_idle, daemon=True).start()
    BoundedHTTPServer(("127.0.0.1", 8888), Handler).serve_forever()


if __name__ == "__main__":
    main()
