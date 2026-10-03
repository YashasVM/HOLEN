"""Run with python3 prod/test_on_demand.py; no Docker or production sockets used."""
import http.client
import importlib.util
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("on_demand", Path(__file__).with_name("holen-on-demand.py"))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class Upstream(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", "123")
        self.end_headers()
    def do_GET(self):
        self.send_response(200)
        if self.path == "/api/health":
            body = json.dumps({"status": "ok", "active_jobs": 0}).encode()
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/truncated":
            self.send_header("Content-Length", "100")
            self.end_headers()
            self.wfile.write(b"short")
            self.close_connection = True
        else:
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(b"data: first\n\n")
            self.wfile.flush()
            release.wait(3)
            self.wfile.write(b"data: last\n\n")
            self.close_connection = True


release = threading.Event()
upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
app = proxy.App("unused.yml", 1, 1)
proxy.Handler.app = app
server = ThreadingHTTPServer(("127.0.0.1", 0), proxy.Handler)
for instance in (upstream, server):
    threading.Thread(target=instance.serve_forever, daemon=True).start()
original_connection = http.client.HTTPConnection


def redirected(host, port, **kwargs):
    return original_connection(host, upstream.server_port if port == 8088 else port, **kwargs)


try:
    with patch.object(proxy.http.client, "HTTPConnection", side_effect=redirected):
        assert app.running(), "Readiness must verify backend health"
        client = original_connection("127.0.0.1", server.server_port, timeout=1)
        client.request("GET", "/events")
        response = client.getresponse()
        assert response.read1(1024) == b"data: first\n\n", "SSE must arrive before upstream finishes"
        assert app.active_requests == 1, "Streaming downloads must prevent idle stop"
        app.last_activity = 0
        with patch.object(proxy.subprocess, "run") as run:
            assert not app.stop_if_idle()
            run.assert_not_called()
        release.set()
        assert response.read() == b"data: last\n\n"
        client.close()
        for _ in range(100):
            if not app.active_requests: break
            time.sleep(.01)
        assert app.active_requests == 0
        client = original_connection("127.0.0.1", server.server_port, timeout=1)
        client.request("HEAD", "/file")
        response = client.getresponse()
        assert response.status == 200 and response.getheader("Content-Length") == "123"
        assert response.read() == b""
        client.close()
        for _ in range(100):
            if not app.active_requests: break
            time.sleep(.01)
        assert app.active_requests == 0

        client = original_connection("127.0.0.1", server.server_port, timeout=1)
        client.request("GET", "/truncated")
        response = client.getresponse()
        try:
            response.read()
            raise AssertionError("Truncated downloads must fail instead of hanging")
        except http.client.IncompleteRead as exc:
            assert exc.partial == b"short"
        client.close()
        for _ in range(100):
            if not app.active_requests: break
            time.sleep(.01)
        assert app.active_requests == 0

    app.last_activity = 0
    with patch.object(app, "health", return_value={"status": "ok", "active_jobs": 1}), patch.object(proxy.subprocess, "run") as run:
        assert not app.stop_if_idle(), "Background jobs must keep containers awake"
        run.assert_not_called()
    with patch.object(app, "health", return_value=None), patch.object(proxy.subprocess, "run") as run:
        assert not app.stop_if_idle(), "Unknown job state must never trigger shutdown"
        run.assert_not_called()
    with patch.object(app, "health", return_value={"status": "ok", "active_jobs": 0}), patch.object(proxy.subprocess, "run") as run:
        assert app.stop_if_idle()
        assert run.call_args.args[0][-1] == "stop"
        assert app.ready_until == 0
    with patch.object(app, "running", return_value=False), patch.object(proxy.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "docker")):
        assert not app.ensure_running() and app.error == "startup_failed"
    assert proxy.LOADING_PAGE.count(b"<html") == 1
    assert b"fonts.googleapis.com" not in proxy.LOADING_PAGE
    print("PASS: immediate SSE, HEAD, truncated downloads, active stream/job idle guards, failed readiness, startup error")
finally:
    release.set()
    server.shutdown()
    upstream.shutdown()
    server.server_close()
    upstream.server_close()
