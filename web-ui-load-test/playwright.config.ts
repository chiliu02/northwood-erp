import { defineConfig } from "@playwright/test";

// The Web-UI execution drives the real erp-web-ui SPA on the Vite dev server
// (:5174), which proxies /api + /oauth2 + /login to the ERP BFF (:8089) — the
// genuine BFF-as-proxy OIDC path (tokens never reach the browser). Concurrency
// is managed *inside* the single spec (one browser context per simulated user,
// run together) so the shared backend sees real concurrent distinct-user load —
// not across Playwright workers.
export default defineConfig({
  testDir: "./tests",
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.SPA_BASE ?? "http://localhost:5174",
    headless: process.env.HEADED ? false : true,
    ignoreHTTPSErrors: true,
  },
});
