"""Deploy Holen to a server via SSH/SFTP using local SSH credentials and .env."""
import os
import paramiko

HOST = os.environ.get("HOLEN_DEPLOY_HOST", "")
USER = os.environ.get("HOLEN_DEPLOY_USER", "")
PASSWORD = os.environ.get("HOLEN_DEPLOY_PASSWORD")
REMOTE_BASE = os.environ.get("HOLEN_DEPLOY_PATH", "")

if not all((HOST, USER, REMOTE_BASE)):
    raise RuntimeError(
        "Set HOLEN_DEPLOY_HOST, HOLEN_DEPLOY_USER, and HOLEN_DEPLOY_PATH. "
        "Use an SSH key; HOLEN_DEPLOY_PASSWORD is only an optional fallback."
    )

# All files to upload (relative path -> content)
FILES = {}

def read_local(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()

LOCAL_ROOT = os.path.dirname(os.path.abspath(__file__))
if not os.path.isfile(os.path.join(LOCAL_ROOT, ".env")):
    raise RuntimeError("Create a local .env from .env.example before deploying.")

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

print(f"Connecting to {USER}@{HOST}...")
client = paramiko.SSHClient()
client.load_system_host_keys()
client.set_missing_host_key_policy(paramiko.RejectPolicy())
connect_kwargs = {"hostname": HOST, "username": USER, "timeout": 15}
if PASSWORD:
    connect_kwargs["password"] = PASSWORD
client.connect(**connect_kwargs)
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
ssh_exec(f"cd {REMOTE_BASE} && docker compose config --quiet")
ssh_exec(f"cd {REMOTE_BASE} && docker compose down --remove-orphans 2>/dev/null || true", check=False)
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
print(f"App deployment completed for {HOST}.")
