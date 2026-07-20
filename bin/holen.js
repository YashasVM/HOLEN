#!/usr/bin/env node

import { cpSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);

function printUsage() {
  console.log("Usage: npx github:YashasVM/HOLEN [--dir <directory>]");
  console.log("\nInstalls Holen into ./holen by default, then starts it with Docker Compose.");
}

if (args.includes("--help") || args.includes("-h")) {
  printUsage();
  process.exit(0);
}

const dirIndex = args.indexOf("--dir");
if (dirIndex !== -1 && (!args[dirIndex + 1] || args.length !== 2)) {
  console.error("Use --dir followed by one directory name.");
  process.exit(1);
}
if (dirIndex === -1 && args.length) {
  printUsage();
  process.exit(1);
}

const projectDir = resolve(process.cwd(), dirIndex === -1 ? "holen" : args[dirIndex + 1]);
if (existsSync(projectDir) && readdirSync(projectDir).length) {
  console.error(`Refusing to overwrite the existing directory: ${projectDir}`);
  console.error(`Run 'cd ${projectDir} && ./run.sh' to start that installation.`);
  process.exit(1);
}

const dockerCheck = spawnSync("docker", ["compose", "version"], { stdio: "ignore" });
if (dockerCheck.status !== 0) {
  console.error("Docker Engine with Docker Compose v2 is required.");
  process.exit(1);
}

mkdirSync(projectDir, { recursive: true });
for (const entry of ["backend", "frontend", "docker-compose.yml", ".env.example", "run.sh", "README.md", "LICENSE"]) {
  const source = resolve(packageRoot, entry);
  if (existsSync(source)) cpSync(source, resolve(projectDir, entry), { recursive: true });
}

console.log(`Installed Holen in ${projectDir}`);
const result = spawnSync("docker", ["compose", "up", "--build", "-d"], {
  cwd: projectDir,
  stdio: "inherit",
});
if (result.status !== 0) process.exit(result.status ?? 1);

console.log("\nHolen is starting at http://localhost:8080");
console.log(`Manage it with: cd ${projectDir} && docker compose ps`);
