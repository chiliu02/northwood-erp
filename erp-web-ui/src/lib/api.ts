/**
 * Thin fetch wrapper. The Vite dev proxy forwards /api/* to the BFF on
 * :8089; in production the SPA is served from the BFF itself so the
 * paths still resolve. No baseURL needed.
 *
 * Slice A: a 401 from the BFF means there's no live OIDC session; we
 * navigate the browser to /oauth2/authorization/keycloak so Spring Security
 * starts the code flow against Keycloak. Once the user logs in and Keycloak
 * redirects back to /login/oauth2/code/keycloak, the BFF stamps a session
 * cookie and the SPA's next request succeeds. No tokens ever touch the
 * browser — see option (a) in dev-todo §1.
 */
export async function apiGet<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: "include",
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 401) {
    redirectToLogin();
    throw new ApiError(401, "redirecting to login");
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, text || res.statusText);
  }
  return res.json();
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return apiWrite<T>("POST", path, body);
}

export async function apiPut<T>(path: string, body?: unknown): Promise<T> {
  return apiWrite<T>("PUT", path, body);
}

async function apiWrite<T>(method: "POST" | "PUT", path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    redirectToLogin();
    throw new ApiError(401, "redirecting to login");
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, text || res.statusText);
  }
  // 204 No Content has no body
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get("content-type") ?? "";
  return ct.includes("application/json") ? res.json() : (undefined as T);
}

let redirecting = false;
function redirectToLogin() {
  if (redirecting) return;
  redirecting = true;
  // Spring Security's default OAuth2 client login URL. Always sent through
  // the BFF (Vite's dev proxy in development; same-origin in production)
  // so the redirect chain hits the BFF's session cookie domain.
  window.location.href = "/oauth2/authorization/keycloak";
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}
