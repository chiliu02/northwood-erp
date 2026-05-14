import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { apiGet } from "./api";

export interface CurrentUser {
  username: string;
  fullName: string | null;
  roles: string[];
}

interface UserContextValue {
  me: CurrentUser | null;
  hasRole: (role: string) => boolean;
  refresh: () => Promise<void>;
}

const UserContext = createContext<UserContextValue>({
  me: null,
  hasRole: () => false,
  refresh: async () => {},
});

/**
 * Loads {@code /api/me} once on mount and provides {@code hasRole(role)} to
 * descendants. Slice D: action buttons read this to gate themselves with a
 * "requires role: X" tooltip when the current persona lacks the role.
 */
export function UserProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<CurrentUser | null>(null);

  const refresh = async () => {
    try {
      const next = await apiGet<CurrentUser>("/api/me");
      setMe(next);
    } catch {
      // 401 handled by api wrapper (redirects to /oauth2/authorization/keycloak).
      // Other errors leave me as null; consumers render the loading shape.
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  return (
    <UserContext.Provider
      value={{
        me,
        hasRole: (role: string) => Boolean(me?.roles?.includes(role)),
        refresh,
      }}
    >
      {children}
    </UserContext.Provider>
  );
}

export function useCurrentUser() {
  return useContext(UserContext);
}
