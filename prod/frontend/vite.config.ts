import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    // Loopback-only dev server. Do NOT set 0.0.0.0 here: binding all
    // interfaces exposes the dev server (with HMR/code exec) to the LAN.
    // NOTE: package.json `dev`/`preview` scripts must not pass
    // `--host 0.0.0.0` either, since the CLI flag overrides this.
    host: "127.0.0.1",
    proxy: {
      "/api": "http://127.0.0.1:8000",
    },
  },
  // No preview.host set: `vite preview` defaults to loopback. Only add
  // `preview: { host: "0.0.0.0" }` (or --host) when serving a preview from
  // inside a container that needs external access.
  build: {
    target: "es2020",
  },
});
