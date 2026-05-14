import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// One proxy entry: everything under /api/* goes to the demo BFF on :8080.
// The BFF (demo-web-ui-bff Spring Boot module) holds the per-service routing
// table and the aggregated saga SSE composition. Before the BFF landed,
// this file used to enumerate ~15 path-prefix → port mappings; now it's
// just the single rule. Sibling: erp-web-ui talks to erp-web-ui-bff on :8089.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
