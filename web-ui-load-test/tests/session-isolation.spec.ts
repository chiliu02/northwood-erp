import { test, expect, chromium, type BrowserContext, type Page } from "@playwright/test";

/**
 * Web-UI execution of the concurrent load test (docs/concurrent-load-test.md §5) —
 * the *fidelity* run. N simulated users, each in its own isolated browser context,
 * log into the real erp-web-ui SPA via the genuine Keycloak OIDC code flow (one
 * distinct Keycloak identity per context), then place an order over the real
 * SPA -> BFF -> sales-service path. The properties asserted are the front-end /
 * BFF concurrency-safety ones the REST stress run cannot reach:
 *
 *   - Session isolation / no token bleed — each context's /api/me returns *its own*
 *     username; the N usernames are all distinct.
 *   - Distinct created_by — each user successfully places an order (HTTP 201) with a
 *     distinct order id, so N distinct identities flow end-to-end (verified server-
 *     side: order created_by = preferred_username; the conservation invariants are
 *     the shared Java InvariantVerifier finale).
 *
 * Deliberately bounded (~10–50 sessions per the design) — contention is a backend property
 * proven by the Gatling run; this proves the real front-end path drives the same
 * correct flow for concurrent, distinct, real users.
 *
 * Prerequisites (LIVE): the stack up, erp-web-ui Vite dev server on :5174, the ERP
 * BFF on :8089, and provision-keycloak.ps1 having created the user-N load users.
 */

const SPA = process.env.SPA_BASE ?? "http://localhost:5174";
const UI_USERS = Number(process.env.UI_USERS ?? 5);
const PASSWORD = process.env.UI_PASSWORD ?? "password";
const CUSTOMER_CODE = "CUST-001";
const PRODUCT = {
  id: "00000000-0000-7000-8000-000000000400",
  sku: "FG-CHAIR-001",
  name: "Wooden Dining Chair",
};

interface UserOutcome {
  username: string;
  meUsername: string;
  orderId: string;
  orderStatus: number;
}

/** Complete the Keycloak OIDC code flow for one context, landing back on the SPA. */
async function login(page: Page, username: string): Promise<void> {
  await page.goto(SPA + "/");
  // The SPA's first /api/me 401s and redirects the browser to Keycloak.
  await page.waitForURL(/\/realms\/northwood\/protocol\/openid-connect\/auth/, { timeout: 60_000 });
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(PASSWORD);
  await Promise.all([
    page.waitForURL((url) => url.origin === new URL(SPA).origin, { timeout: 60_000 }),
    page.locator("#kc-login").click(),
  ]);
}

/** One simulated user: login, confirm identity, place an order — all on its own context. */
async function runUser(context: BrowserContext, username: string, index: number): Promise<UserOutcome> {
  const page = await context.newPage();
  await login(page, username);

  // Session isolation: /api/me must report *this* context's user (carried by the
  // BFF session cookie this context holds — no bleed from any other context).
  const meResp = await page.request.get("/api/me");
  expect(meResp.ok(), `/api/me for ${username}: HTTP ${meResp.status()}`).toBeTruthy();
  const me = await meResp.json();

  // Place an order over the real BFF session (the exact path the SPA's
  // SalesOrderNew uses: POST /api/sales-cmd/sales-orders — the BFF's write alias
  // rewrites /api/sales-cmd -> sales-service /api; /api/sales-orders itself routes
  // to reporting's read-only 360 projection).
  const orderNumber = `UI-${index}-${Date.now()}`;
  const orderResp = await page.request.post("/api/sales-cmd/sales-orders", {
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    data: {
      orderNumber,
      customerCode: CUSTOMER_CODE,
      currencyCode: "AUD",
      paymentTerms: "on_shipment",
      lines: [{ productId: PRODUCT.id, productSku: PRODUCT.sku, productName: PRODUCT.name, orderedQuantity: 1 }],
    },
  });
  const orderJson = orderResp.status() === 201 ? await orderResp.json() : { id: "" };
  return { username, meUsername: me.username, orderId: orderJson.id, orderStatus: orderResp.status() };
}

test("concurrent distinct-user browser sessions stay isolated and each places an order", async () => {
  const browser = await chromium.launch();
  const usernames = Array.from({ length: UI_USERS }, (_, i) => `user-${i}`);
  const contexts: BrowserContext[] = [];
  try {
    // One isolated context per user (separate cookie jar = separate BFF session).
    for (let i = 0; i < UI_USERS; i++) {
      contexts.push(await browser.newContext());
    }
    // Run them together so the backend sees concurrent distinct-user load.
    const outcomes = await Promise.all(usernames.map((u, i) => runUser(contexts[i], u, i)));

    // No token bleed: each context saw its own identity.
    for (const o of outcomes) {
      expect(o.meUsername, `context for ${o.username} must see its own /api/me`).toBe(o.username);
      expect(o.orderStatus, `${o.username} place-order status`).toBe(201);
      expect(o.orderId, `${o.username} order id`).not.toBe("");
    }
    // Session isolation across contexts: all identities + all order ids distinct.
    const seenUsers = new Set(outcomes.map((o) => o.meUsername));
    const seenOrders = new Set(outcomes.map((o) => o.orderId));
    expect(seenUsers.size, "all browser sessions must be distinct identities").toBe(UI_USERS);
    expect(seenOrders.size, "all orders must be distinct").toBe(UI_USERS);
  } finally {
    await Promise.all(contexts.map((c) => c.close()));
    await browser.close();
  }
});
