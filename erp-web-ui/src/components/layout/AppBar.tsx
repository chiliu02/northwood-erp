import { Bell, ChevronDown, LogOut, Search } from "lucide-react";
import { useCurrentUser } from "../../lib/UserContext";

/**
 * Top app bar: brand mark, search, notifications, user chip, and logout.
 * The user chip + role are sourced from {@link useCurrentUser}; on 401 the
 * api wrapper redirects to Keycloak so this component never has to handle
 * the unauthenticated state explicitly. Persona switching happens through
 * Keycloak's login form (sign out, then sign in as the desired persona).
 */
export function AppBar() {
  const { me } = useCurrentUser();

  const initials = me?.username ? me.username.slice(0, 2).toUpperCase() : "—";
  const display = me?.fullName ?? me?.username ?? "Loading…";
  const primaryRole = me?.roles?.find((r) => !r.startsWith("default-roles")) ?? "";

  return (
    <header className="flex h-14 shrink-0 items-center border-b border-border-default bg-bg-surface px-4">
      <div className="flex items-center gap-2 pr-6">
        <div className="flex h-7 w-7 items-center justify-center rounded bg-brand-primary text-text-on-brand">
          <span className="text-sm font-semibold">N</span>
        </div>
        <span className="text-base font-semibold text-text-primary">Northwood ERP</span>
      </div>

      <div className="flex flex-1 items-center justify-center px-8">
        <div className="relative w-full max-w-xl">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-faint" />
          <input
            type="search"
            placeholder="Search orders, products, suppliers…"
            className="h-9 w-full rounded-md border border-border-default bg-bg-subtle pl-9 pr-3 text-sm placeholder:text-text-faint focus:border-border-focus focus:bg-bg-surface focus:outline-none"
          />
        </div>
      </div>

      <div className="flex items-center gap-1">
        <button
          type="button"
          className="flex h-9 w-9 items-center justify-center rounded-md text-text-secondary hover:bg-bg-subtle"
          aria-label="Notifications"
          title="Notifications (not wired yet)"
        >
          <Bell className="h-5 w-5" />
        </button>

        <div className="ml-2 h-6 w-px bg-border-default" />

        <button
          type="button"
          className="ml-2 flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-bg-subtle"
        >
          <div className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-primary-soft text-xs font-semibold text-brand-primary">
            {initials}
          </div>
          <div className="text-left">
            <div className="text-xs font-medium text-text-primary">{display}</div>
            <div className="text-[11px] text-text-muted">{primaryRole}</div>
          </div>
          <ChevronDown className="h-4 w-4 text-text-muted" />
        </button>

        <form method="post" action="/logout" className="ml-1">
          <button
            type="submit"
            className="flex h-9 w-9 items-center justify-center rounded-md text-text-secondary hover:bg-bg-subtle"
            aria-label="Sign out"
            title="Sign out"
          >
            <LogOut className="h-5 w-5" />
          </button>
        </form>
      </div>
    </header>
  );
}
