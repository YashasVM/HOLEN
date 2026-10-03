"""Run: python checks/download_regression.py (install backend requirements + httpx)."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch

from fastapi.testclient import TestClient


def main():
    with tempfile.TemporaryDirectory() as temp:
        os.environ.update(DOWNLOAD_DIR=f"{temp}/downloads", SQLITE_PATH=f"{temp}/test.db", DEFAULT_USAGE_LIMIT_GB="20", DOWNLOAD_ACCEL_REDIRECT="false", CLERK_AUDIENCE="")
        spec = importlib.util.spec_from_file_location("holen_check", Path(__file__).resolve().parents[1] / "app/main.py")
        app = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(app)
        with app.open_db() as db:
            db.execute("INSERT INTO access_users (user_id, usage_limit_bytes, created_at, updated_at) VALUES ('owner', 1000, ?, ?)", (app.now_iso(), app.now_iso()))
            db.execute("INSERT INTO access_users (user_id, usage_limit_bytes, created_at, updated_at) VALUES ('other', 1000, ?, ?)", (app.now_iso(), app.now_iso()))
            for job_id, owner in [('one', 'owner'), ('other', 'other')]:
                path = app.DOWNLOAD_DIR / f"{job_id}.test.mp4"
                path.write_bytes(b'0123456789')
                db.execute("INSERT INTO jobs (id,url,format,status,created_at,updated_at,user_email,file_path,expires_at) VALUES (?, 'https://youtu.be/example', 'best', 'completed', ?, ?, ?, ?, '2000-01-01')", (job_id, app.now_iso(), app.now_iso(), owner, str(path)))
        def owner():
            with app.open_db() as db:
                return dict(db.execute("SELECT * FROM access_users WHERE user_id='owner'").fetchone())
        app.app.dependency_overrides[app.require_user] = owner
        client = TestClient(app.app)
        assert client.post('/api/jobs/other/download-ticket').status_code == 404
        assert client.post('/api/jobs/one/download-ticket').status_code == 200  # Cached expired links can be refreshed.
        assert client.head('/api/jobs/one/download').status_code == 200
        assert owner()['egress_bytes'] == 0
        response = client.get('/api/jobs/one/download', headers={'Range': 'bytes=3-5'})
        assert response.status_code == 206 and response.content == b'345'
        assert client.get('/api/jobs/one/download', headers={'Range': 'bytes=6-'}).content == b'6789'
        assert owner()['egress_bytes'] == 7
        assert client.get('/api/jobs/one/download', headers={'Range': 'bytes=20-'}).status_code == 416
        assert client.get('/api/jobs/one/download', headers={'Range': 'bytes=0-1,3-4'}).status_code == 416
        assert owner()['egress_bytes'] == 7
        assert client.get('/api/jobs/one/download', headers={'Range': 'bytes=0-1', 'If-Range': '"stale"'}).content == b'0123456789'
        assert owner()['egress_bytes'] == 17
        app.DOWNLOAD_ACCEL_REDIRECT = True
        response = client.get('/api/jobs/one/download', headers={'Range': 'bytes=0-1'})
        assert response.headers['x-accel-redirect'] == '/_downloads/one.test.mp4'
        assert response.headers['cache-control'] == 'private, no-store'
        assert owner()['egress_bytes'] == 19
        app.DOWNLOAD_ACCEL_REDIRECT = False
        with app.open_db() as db:
            db.execute("UPDATE access_users SET usage_limit_bytes=29 WHERE user_id='owner'")
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(lambda _: client.get('/api/jobs/one/download').status_code, range(2)))
        assert sorted(statuses) == [200, 429] and owner()['egress_bytes'] == 29
        cookie = client.cookies.get('holen_download_ticket')
        with app.open_db() as db:
            db.execute("UPDATE download_tickets SET expires_at='2000-01-01'")
        assert client.get('/api/jobs/one/download').status_code == 401
        assert cookie and app.usage_limit_for_email('unknown@example.org') == 20 * 1024**3
        with app.open_db() as db:
            db.execute("UPDATE access_users SET usage_limit_bytes=? WHERE user_id='owner'", (5 * 1024**3,))
            db.execute("PRAGMA user_version = 0")
        app.init_db()
        assert owner()['usage_limit_bytes'] == 20 * 1024**3
        with app.open_db() as db:
            db.execute("UPDATE access_users SET usage_limit_bytes=? WHERE user_id='owner'", (2 * 1024**3,))
        app.init_db()
        assert owner()['usage_limit_bytes'] == 2 * 1024**3  # Subsequent custom changes survive.
        assert app.CLERK_AUDIENCE == ''
        assert app.format_selector('mp3') == 'ba/b'
        assert 'height<=720' in app.format_selector('720p')
        info = {'title': 'video', 'duration': 10, 'requested_formats': [{'filesize': 100}, {'filesize': 20}], 'formats': [{'format_id': 'large', 'filesize': 999999, 'height': 2160, 'vcodec': 'h264', 'acodec': 'none'}]}
        with patch.object(app.subprocess, 'run', return_value=type('Result', (), {'returncode': 0, 'stdout': json.dumps(info)})()) as run:
            meta = app.run_metadata('https://youtu.be/example', '720p')
            assert meta['reserved_bytes'] == 264
            assert app.run_metadata('https://youtu.be/example', '720p') is meta and run.call_count == 1
        (app.DOWNLOAD_DIR / 'output.test.mp4').write_bytes(b'a')
        (app.DOWNLOAD_DIR / 'output.test.jpg').write_bytes(b'b')
        (app.DOWNLOAD_DIR / 'output.test.mp4.part').write_bytes(b'c')
        assert app.detect_output_file('output').suffix == '.mp4'
        app.CACHE_LIMIT_GB = 1 / 1024**3
        with app.open_db() as db:
            db.execute("INSERT INTO jobs (id,url,format,status,created_at,updated_at,user_email) VALUES ('active','x','best','running',?,?,'owner')", (app.now_iso(), app.now_iso()))
        active_path = app.DOWNLOAD_DIR / 'active.mp4.part'
        active_path.write_bytes(b'partial')
        app.cleanup_expired()
        assert active_path.exists()
        assert app.health()['active_jobs'] == 1
        print('PASS: resumable authorization, byte quotas, concurrent enforcement, cached links, selected format reservations, safe cleanup, nginx offload, health')


if __name__ == '__main__':
    main()
