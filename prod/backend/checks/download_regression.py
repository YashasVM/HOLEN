"""Run: python checks/download_regression.py (install backend requirements + httpx)."""
import asyncio
import concurrent.futures
import importlib.util
import json
import os
import sqlite3
from pathlib import Path
import tempfile
from unittest.mock import patch

from fastapi.testclient import TestClient
import yt_dlp_ejs  # Required by yt-dlp for YouTube JavaScript challenges.


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
        assert client.post('/api/jobs/one/download-ticket').status_code == 429
        with app.open_db() as db:
            db.execute("UPDATE access_users SET usage_limit_bytes=1000 WHERE user_id='owner'")
        missing = app.DOWNLOAD_DIR / 'one.test.mp4'
        missing.rename(missing.with_suffix('.hidden'))
        assert client.post('/api/jobs/one/download-ticket').status_code == 404
        missing.with_suffix('.hidden').rename(missing)
        with app.open_db() as db:
            db.execute("UPDATE jobs SET file_path=? WHERE id='one'", (str(Path(temp) / 'outside.mp4'),))
        assert client.post('/api/jobs/one/download-ticket').status_code == 403
        with app.open_db() as db:
            db.execute("UPDATE jobs SET file_path=? WHERE id='one'", (str(missing),))
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
        key = type('Key', (), {'key': 'mock-key'})()
        with patch.object(app, 'CLERK_FRONTEND_API_URL', 'https://test.clerk.accounts.dev'), patch.object(app, '_clerk_jwks', object()), patch.object(app, '_clerk_signing_key', return_value=key), patch.object(app.jwt, 'decode', return_value={'sub': 'owner', 'azp': 'http://127.0.0.1:5173'}):
            assert app.verify_token('mock')['sub'] == 'owner'
        with patch.object(app, 'CLERK_FRONTEND_API_URL', 'https://test.clerk.accounts.dev'), patch.object(app, '_clerk_jwks', object()), patch.object(app, '_clerk_signing_key', return_value=key), patch.object(app.jwt, 'decode', return_value={'sub': 'owner', 'azp': 'https://untrusted.example'}):
            try:
                app.verify_token('mock')
                raise AssertionError('Untrusted authorized party was accepted')
            except app.HTTPException as exc:
                assert exc.status_code == 401
        cors = client.options('/api/jobs/one/download', headers={'Origin': 'http://127.0.0.1:5173', 'Access-Control-Request-Method': 'HEAD', 'Access-Control-Request-Headers': 'Range,If-Range'})
        assert cors.status_code == 200 and cors.headers['access-control-allow-origin'] == 'http://127.0.0.1:5173'
        assert app.format_selector('mp3') == 'ba/b'
        assert 'height<=720' in app.format_selector('720p')
        info = {'title': 'video', 'duration': 10, 'requested_formats': [{'filesize': 100}, {'filesize': 20}], 'formats': [{'format_id': 'large', 'filesize': 999999, 'height': 2160, 'vcodec': 'h264', 'acodec': 'none'}]}
        with patch.object(app.subprocess, 'run', return_value=type('Result', (), {'returncode': 0, 'stdout': json.dumps(info)})()) as run:
            meta = app.run_metadata('https://youtu.be/example', '720p')
            assert meta['reserved_bytes'] == 264
            assert app.run_metadata('https://youtu.be/example', '720p') is meta and run.call_count == 1
        with patch.object(app.subprocess, 'run', return_value=type('Result', (), {'returncode': 0, 'stdout': json.dumps(info)})()):
            audio = app.run_metadata('https://youtu.be/example', 'mp3')
            assert audio['reserved_bytes'] >= 2 * 10 * 192000 / 8
        live_info = {**info, 'is_live': True}
        with patch.object(app.subprocess, 'run', return_value=type('Result', (), {'returncode': 0, 'stdout': json.dumps(live_info)})()):
            try:
                app.run_metadata('https://youtu.be/live', 'best')
                raise AssertionError('Live stream was accepted')
            except app.HTTPException as exc:
                assert exc.status_code == 400
        assert '192K' in app.command_for('audio', 'https://youtu.be/example', 'mp3')
        async def empty_lines():
            if False:
                yield b''
        class Process:
            returncode = 0
            def __init__(self, code=0):
                self.returncode = code
                self.stdout = empty_lines()
            async def wait(self):
                return self.returncode
        def insert_job(job_id):
            with app.open_db() as db:
                db.execute("INSERT INTO jobs (id,url,format,status,created_at,updated_at,user_email,reserved_bytes) VALUES (?,'x','best','running',?,?,'owner',100)", (job_id, app.now_iso(), app.now_iso()))
        insert_job('finish')
        final_path = app.DOWNLOAD_DIR / 'finish.video.mp4'
        final_path.write_bytes(b'finished')
        class AtomicConnection(sqlite3.Connection):
            def commit(self):
                super().commit()
                row = self.execute("SELECT status, file_path FROM jobs WHERE id='finish'").fetchone()
                if row and row['status'] == 'completed':
                    assert row['file_path'] == str(final_path), 'Completion was published before its file reference'
        def checked_db():
            conn = sqlite3.connect(app.SQLITE_PATH, factory=AtomicConnection)
            conn.row_factory = sqlite3.Row
            return conn
        before = owner()['ingress_bytes']
        async def spawn(*args, **kwargs):
            return Process()
        with patch.object(app, 'open_db', checked_db), patch.object(app.asyncio, 'create_subprocess_exec', spawn), patch.object(app, 'cleanup_expired', side_effect=PermissionError('simulated housekeeping failure')), patch.object(app.logging, 'getLogger'):
            asyncio.run(app.run_job('finish', 'x', 'best'))
        assert app.get_job_or_404('finish')['status'] == 'completed'
        assert owner()['ingress_bytes'] == before + 8  # No second failed-job charge after cleanup errors.
        insert_job('failed')
        (app.DOWNLOAD_DIR / 'failed.video.mp4.part').write_bytes(b'partial')
        async def fail_spawn(*args, **kwargs):
            return Process(1)
        before = owner()['ingress_bytes']
        with patch.object(app.asyncio, 'create_subprocess_exec', fail_spawn):
            asyncio.run(app.run_job('failed', 'x', 'best'))
        assert owner()['ingress_bytes'] == before + 7
        insert_job('cancelled')
        (app.DOWNLOAD_DIR / 'cancelled.audio.m4a.part').write_bytes(b'audio')
        app.update_job('cancelled', status='cancelled')
        before = owner()['ingress_bytes']
        asyncio.run(app.run_job('cancelled', 'x', 'best'))
        assert owner()['ingress_bytes'] == before + 5
        insert_job('broken')
        (app.DOWNLOAD_DIR / 'broken.video.mp4.part').write_bytes(b'broken')
        async def broken_lines():
            raise RuntimeError('simulated output reader failure')
            yield b''
        class BrokenProcess:
            returncode = None
            terminated = False
            stdout = broken_lines()
            def terminate(self):
                self.terminated = True
            async def wait(self):
                self.returncode = -15
                return self.returncode
        broken = BrokenProcess()
        async def broken_spawn(*args, **kwargs):
            return broken
        before = owner()['ingress_bytes']
        with patch.object(app.asyncio, 'create_subprocess_exec', broken_spawn):
            asyncio.run(app.run_job('broken', 'x', 'best'))
        assert broken.terminated and owner()['ingress_bytes'] == before + 6
        insert_job('interrupted')
        (app.DOWNLOAD_DIR / 'interrupted.video.mp4.part').write_bytes(b'restart')
        before = owner()['ingress_bytes']
        app.reset_interrupted_jobs()
        app.reset_interrupted_jobs()
        assert owner()['ingress_bytes'] == before + 7  # Restart accounting happens once.
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
        print('PASS: resumable delivery, quota concurrency, ticket preflight, atomic completion, nonfatal cleanup, audio estimates, failed/cancelled/restart accounting, process termination, live-stream rejection')


if __name__ == '__main__':
    main()
