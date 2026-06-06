"""Deploy yt-yvmx to the Linux homelab server via SSH/SFTP."""
import os
import secrets
import paramiko

HOST = "192.168.1.8"
USER = "yvm"
PASSWORD = "890"
REMOTE_BASE = "/home/yvm/yt-yvmx"

# All files to upload (relative path -> content)
FILES = {}

def read_local(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

LOCAL_ROOT = os.path.dirname(os.path.abspath(__file__))

# Collect all project files (excluding node_modules, dist, .git, __pycache__, data, downloads)
SKIP_DIRS = {"node_modules", "dist", ".git", "__pycache__", "data", "downloads", ".gemini"}
SKIP_FILES = {"deploy_to_server.py", "package-lock.json"}

for root, dirs, files in os.walk(LOCAL_ROOT):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for filename in files:
        if filename in SKIP_FILES:
            continue
        local_path = os.path.join(root, filename)
        rel_path = os.path.relpath(local_path, LOCAL_ROOT).replace("\\", "/")
        try:
            FILES[rel_path] = read_local(local_path)
        except (UnicodeDecodeError, PermissionError):
            print(f"  Skipping binary/unreadable: {rel_path}")

# Generate a production .env
APP_SECRET = secrets.token_hex(32)
PROD_ENV = f"""APP_PASSWORD=yvmx-dl-2026
APP_SECRET={APP_SECRET}
PUBLIC_ORIGIN=http://192.168.1.8:8080
DOWNLOAD_DIR=./downloads
SQLITE_PATH=./data/app.db
MAX_DURATION_SECONDS=7200
MAX_ACTIVE_JOBS=1
MAX_QUEUED_JOBS=5
MAX_TEMP_GB=20
FILE_TTL_SECONDS=3600
CLEANUP_INTERVAL_SECONDS=900
CLOUDFLARED_TOKEN=
"""

FILES[".env"] = PROD_ENV

print(f"Connecting to {USER}@{HOST}...")
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, username=USER, password=PASSWORD, timeout=15)
sftp = client.open_sftp()

def ssh_exec(cmd, check=True):
    print(f"  $ {cmd}")
    stdin, stdout, stderr = client.exec_command(cmd, timeout=300)
    out = stdout.read().decode(errors="replace").strip()
    err = stderr.read().decode(errors="replace").strip()
    exit_code = stdout.channel.recv_exit_status()
    if out:
        print(f"    {out[:2000]}")
    if err:
        print(f"    ERR: {err[:2000]}")
    if check and exit_code != 0:
        print(f"    EXIT CODE: {exit_code}")
    return out, err, exit_code

def ensure_remote_dir(path):
    """Create remote directory tree. Path must be absolute."""
    if not path.startswith("/"):
        path = f"/{path}"
    parts = [p for p in path.split("/") if p]
    current = ""
    for part in parts:
        current = f"{current}/{part}"
        try:
            sftp.stat(current)
        except (FileNotFoundError, IOError):
            try:
                sftp.mkdir(current)
            except (IOError, OSError):
                pass  # may already exist from race

# Clean existing directory (but preserve data/)
print(f"\nPreparing remote directory: {REMOTE_BASE}")
ssh_exec(f"mkdir -p {REMOTE_BASE}/downloads {REMOTE_BASE}/data")

# Upload all files
print(f"\nUploading {len(FILES)} files...")
for rel_path, content in sorted(FILES.items()):
    remote_path = f"{REMOTE_BASE}/{rel_path}"
    remote_dir = os.path.dirname(remote_path).replace("\\", "/")
    if remote_dir:
        ensure_remote_dir(remote_dir)
    with sftp.open(remote_path, "w") as f:
        f.write(content)
    print(f"  [OK] {rel_path}")

sftp.close()

# Build and start with Docker Compose
print("\n--- Building and starting with Docker Compose ---")
ssh_exec(f"cd {REMOTE_BASE} && cat .env | head -3")
ssh_exec(f"cd {REMOTE_BASE} && docker compose down --remove-orphans 2>/dev/null; true", check=False)
print("\nBuilding images (this may take a few minutes)...")
out, err, code = ssh_exec(f"cd {REMOTE_BASE} && docker compose build --no-cache 2>&1")
if code != 0:
    print(f"\n!!! Build failed with exit code {code}")
    print("Checking logs...")
else:
    print("\nStarting services...")
    ssh_exec(f"cd {REMOTE_BASE} && docker compose up -d")
    import time
    time.sleep(5)
    print("\n--- Checking service status ---")
    ssh_exec(f"cd {REMOTE_BASE} && docker compose ps")
    print("\n--- Checking health ---")
    ssh_exec("curl -s http://127.0.0.1:8080/api/health || echo 'Health check not ready yet'")
    print("\n--- Checking logs (last 20 lines) ---")
    ssh_exec(f"cd {REMOTE_BASE} && docker compose logs --tail=20 2>&1")

client.close()
print("\n=== Deployment complete ===")
print(f"App should be accessible at: http://192.168.1.8:8080")
print(f"Password: yvmx-dl-2026")
