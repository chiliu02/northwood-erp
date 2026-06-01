import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// All /api, /oauth2, /login, /logout calls are proxied to the ERP BFF on
// :8089. /api/* hits the routing table → backend services. /oauth2/*,
// /login/*, /logout are owned by Spring Security on the BFF (OIDC code
// flow + session lifecycle).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5174,
    proxy: {
      "/api": "http://localhost:8089",
      "/oauth2": "http://localhost:8089",
      "/login": "http://localhost:8089",
      "/logout": "http://localhost:8089",
    },
  },
});
