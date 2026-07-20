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
if (!runInPlace && existsSync(projectDir) && readdirSync(projectDir).length) {
  fail(`Refusing to overwrite the existing directory: ${projectDir}\nRun 'cd ${projectDir} && ./run.sh' to start that installation.`);
}

const python = process.env.PYTHON || "python3";
if (spawnSync(python, ["-c", "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"], { stdio: "ignore" }).status !== 0) fail("Python 3.10+ is required. Set PYTHON if your Python executable has a different name.");
if (spawnSync("ffmpeg", ["-version"], { stdio: "ignore" }).status !== 0) fail("ffmpeg is required. Install it with your operating system's package manager.");
if (spawnSync("npm", ["--version"], { stdio: "ignore" }).status !== 0) fail("npm is required (Node.js 18+).");

if (!runInPlace) {
  mkdirSync(projectDir, { recursive: true });
  for (const entry of ["backend", "frontend", "bin", ".env.example", ".gitignore", "run.sh", "README.md", "LICENSE", "package.json"]) {
    const source = resolve(packageRoot, entry);
    if (existsSync(source)) cpSync(source, resolve(projectDir, entry), { recursive: true });
  }
  console.log(`Installed Holen in ${projectDir}`);
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

if (!existsSync(virtualPython)) run(python, ["-m", "venv", virtualEnv]);
run(virtualPython, ["-m", "pip", "install", "--disable-pip-version-check", "-q", "-r", "requirements.txt"], { cwd: backendDir });
run("npm", ["ci", "--no-audit", "--no-fund"], { cwd: frontendDir });
run("npm", ["run", "build"], { cwd: frontendDir });

const parsedPort = Number(config.APP_PORT);
const appPort = Number.isInteger(parsedPort) && parsedPort > 0 && parsedPort <= 65535 ? String(parsedPort) : "8080";
const logs = openSync(resolve(projectDir, "holen.log"), "a");
const server = spawn(virtualPython, ["-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", appPort], {
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
console.log(`Stop it with: kill $(cat ${resolve(projectDir, "holen.pid")})`);
