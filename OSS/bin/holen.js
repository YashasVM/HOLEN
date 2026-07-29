#!/usr/bin/env node

import { cpSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);

function printUsage() {
  console.log("Usage: npx github:YashasVM/HOLEN [--dir <directory>]");
  console.log("\nInstalls Holen into ./holen by default, then starts it without Docker.");
}

function fail(message) {
  console.error(message);
  process.exit(1);
}

function run(command, commandArgs, options = {}) {
  const result = spawnSync(command, commandArgs, { stdio: "inherit", ...options });
  if (result.error) fail(`Could not run ${command}: ${result.error.message}`);
  if (result.status !== 0) process.exit(result.status ?? 1);
}

function commandWorks(command, commandArgs) {
  const check = spawnSync(command, commandArgs, { stdio: "ignore" });
  return !check.error && check.status === 0;
}

function findPython() {
  const candidates = process.env.PYTHON
    ? [[process.env.PYTHON, []]]
    : process.platform === "win32"
      ? [["python", []], ["py", ["-3"]]]
      : [["python3", []], ["python", []]];
  for (const [command, prefix] of candidates) {
    if (commandWorks(command, [...prefix, "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"])) {
      return { command, prefix };
    }
  }
  return null;
}

function commandExists(command) {
  return commandWorks(command, ["--version"]);
}

function elevated(command, commandArgs) {
  if (typeof process.getuid !== "function" || process.getuid() === 0) {
    run(command, commandArgs);
    return;
  }
  if (!commandExists("sudo")) {
    fail(`Installing system packages requires administrator privileges. Re-run this command as an administrator, or install the missing packages manually.`);
  }
  run("sudo", [command, ...commandArgs]);
}

function installSystemRequirements({ needsPython, needsFfmpeg }) {
  const missing = [needsPython && "Python 3.10+", needsFfmpeg && "ffmpeg"].filter(Boolean).join(" and ");
  console.log(`Missing ${missing}. Downloading it with your system package manager...`);

  if (process.platform === "darwin") {
    if (!commandExists("brew")) {
      fail("Homebrew is needed to install the missing runtime packages automatically. Install Homebrew from https://brew.sh, then run this command again.");
    }
    const packages = [needsPython && "python@3.12", needsFfmpeg && "ffmpeg"].filter(Boolean);
    run("brew", ["install", ...packages]);
    return;
  }

  if (process.platform === "win32") {
    if (!commandExists("winget")) {
      fail("Windows Package Manager (winget) is needed to install the missing runtime packages automatically. Install App Installer from Microsoft Store, then run this command again.");
    }
    const wingetArgs = ["install", "--exact", "--accept-package-agreements", "--accept-source-agreements", "--silent"];
    if (needsPython) run("winget", [...wingetArgs, "--id", "Python.Python.3.12"]);
    if (needsFfmpeg) run("winget", [...wingetArgs, "--id", "Gyan.FFmpeg"]);
    return;
  }

  if (process.platform === "linux") {
    if (commandExists("apt-get")) {
      elevated("apt-get", ["update"]);
      elevated("apt-get", ["install", "-y", ...[needsPython && "python3", needsPython && "python3-venv", needsFfmpeg && "ffmpeg"].filter(Boolean)]);
      return;
    }
    if (commandExists("dnf")) {
      elevated("dnf", ["install", "-y", ...[needsPython && "python3", needsFfmpeg && "ffmpeg"].filter(Boolean)]);
      return;
    }
    if (commandExists("pacman")) {
      elevated("pacman", ["-Sy", "--noconfirm", ...[needsPython && "python", needsFfmpeg && "ffmpeg"].filter(Boolean)]);
      return;
    }
    if (commandExists("apk")) {
      elevated("apk", ["add", ...[needsPython && "python3", needsFfmpeg && "ffmpeg"].filter(Boolean)]);
      return;
    }
    if (commandExists("zypper")) {
      elevated("zypper", ["--non-interactive", "install", ...[needsPython && "python3", needsFfmpeg && "ffmpeg"].filter(Boolean)]);
      return;
    }
  }

  fail(`Could not find a supported package manager to install ${missing}. Install it manually, then run this command again.`);
}

function ensureRuntimeRequirements() {
  const nodeMajor = Number(process.versions.node.split(".")[0]);
  if (!Number.isInteger(nodeMajor) || nodeMajor < 18 || !commandExists("npm")) {
    fail("Node.js 18+ with npm is required to run this npx launcher.");
  }

  let python = findPython();
  let hasFfmpeg = commandExists("ffmpeg");
  if (!python || !hasFfmpeg) {
    installSystemRequirements({ needsPython: !python, needsFfmpeg: !hasFfmpeg });
    python = findPython();
    hasFfmpeg = commandExists("ffmpeg");
  }

  if (!python || !hasFfmpeg) {
    const missing = [!python && "Python 3.10+", !hasFfmpeg && "ffmpeg"].filter(Boolean).join(" and ");
    fail(`${missing} was installed but is not yet available on PATH. Open a new terminal and run the same command again.`);
  }
  return python;
}

function readEnv(file) {
  if (!existsSync(file)) return {};
  const values = {};
  for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match) continue;
    const [, key, rawValue] = match;
    values[key] = rawValue.replace(/^("|')|("|')$/g, "");
  }
  return values;
}

if (args.includes("--help") || args.includes("-h")) {
  printUsage();
  process.exit(0);
}

const runInPlace = args.length === 1 && args[0] === "--in-place";
const dirIndex = args.indexOf("--dir");
if (!runInPlace && dirIndex !== -1 && (!args[dirIndex + 1] || args.length !== 2)) fail("Use --dir followed by one directory name.");
if (!runInPlace && dirIndex === -1 && args.length) {
  printUsage();
  process.exit(1);
}

const projectDir = runInPlace ? packageRoot : resolve(process.cwd(), dirIndex === -1 ? "holen" : args[dirIndex + 1]);
const existingProject = !runInPlace && existsSync(projectDir) && readdirSync(projectDir).length > 0;
if (existingProject && (!existsSync(resolve(projectDir, "backend", "app", "main.py")) || !existsSync(resolve(projectDir, "frontend", "package.json")))) {
  fail(`Refusing to overwrite a directory that is not a Holen installation: ${projectDir}`);
}

const python = ensureRuntimeRequirements();

if (!runInPlace) {
  mkdirSync(projectDir, { recursive: true });
  for (const entry of ["backend", "frontend", "bin", ".env.example", ".gitignore", "run.sh", "README.md", "LICENSE", "package.json"]) {
    const source = resolve(packageRoot, entry);
    if (existsSync(source)) cpSync(source, resolve(projectDir, entry), { recursive: true, force: true });
  }
  console.log(`${existingProject ? "Updated" : "Installed"} Holen in ${projectDir}`);
}

const envFile = resolve(projectDir, ".env");
if (!existsSync(envFile)) {
  cpSync(resolve(projectDir, ".env.example"), envFile);
  console.log("Created .env from safe defaults.");
}

const config = readEnv(envFile);
const pidFile = resolve(projectDir, "holen.pid");
if (existsSync(pidFile)) {
  const existingPid = Number(readFileSync(pidFile, "utf8").trim());
  try {
    process.kill(existingPid, 0);
    fail(`Holen is already running at http://localhost:${config.APP_PORT || "8080"} (PID ${existingPid}).`);
  } catch (error) {
    if (error.code === "ESRCH") rmSync(pidFile);
    else fail(`Could not verify the existing Holen process (PID ${existingPid}). Remove ${pidFile} only after confirming the server is stopped.`);
  }
}
const backendDir = resolve(projectDir, "backend");
const frontendDir = resolve(projectDir, "frontend");
const virtualEnv = resolve(backendDir, ".venv");
const virtualPython = process.platform === "win32"
  ? resolve(virtualEnv, "Scripts", "python.exe")
  : resolve(virtualEnv, "bin", "python");

if (!existsSync(virtualPython)) run(python.command, [...python.prefix, "-m", "venv", virtualEnv]);
run(virtualPython, ["-m", "pip", "install", "--disable-pip-version-check", "-q", "-r", "requirements.txt"], { cwd: backendDir });
run("npm", ["ci", "--no-audit", "--no-fund"], { cwd: frontendDir });
run("npm", ["run", "build"], { cwd: frontendDir });

const parsedPort = Number(config.APP_PORT);
const appPort = Number.isInteger(parsedPort) && parsedPort > 0 && parsedPort <= 65535 ? String(parsedPort) : "8080";
const appBindHost = config.APP_BIND_HOST || "127.0.0.1";
const allowedBindHosts = new Set(["127.0.0.1", "::1", "0.0.0.0", "::"]);
if (!allowedBindHosts.has(appBindHost)) {
  fail("APP_BIND_HOST must be one of: 127.0.0.1, ::1, 0.0.0.0, ::.");
}
const externallyReachable = appBindHost === "0.0.0.0" || appBindHost === "::";
if (externallyReachable) {
  console.warn("WARNING: Holen will listen on all network interfaces. It has no built-in authentication; use a VPN, firewall, or authenticated reverse proxy.");
}
const logs = openSync(resolve(projectDir, "holen.log"), "a");
const server = spawn(virtualPython, ["-m", "uvicorn", "app.main:app", "--host", appBindHost, "--port", appPort], {
  cwd: backendDir,
  detached: true,
  env: {
    ...process.env,
    ...config,
    DOWNLOAD_DIR: resolve(projectDir, "downloads"),
    SQLITE_PATH: resolve(projectDir, "data", "app.db"),
    FRONTEND_DIST: resolve(frontendDir, "dist"),
  },
  stdio: ["ignore", logs, logs],
});
server.unref();
writeFileSync(pidFile, `${server.pid}\n`);

console.log(`\nHolen is starting at http://localhost:${appPort}`);
const stopCommand = process.platform === "win32"
  ? `Stop-Process -Id (Get-Content '${pidFile}')`
  : `kill $(cat ${pidFile})`;
console.log(`Stop it with: ${stopCommand}`);
