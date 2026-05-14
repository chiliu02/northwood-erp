import { Outlet } from "react-router-dom";
import { AppBar } from "./AppBar";
import { Sidebar } from "./Sidebar";

/**
 * Top-level layout: fixed AppBar on top, fixed Sidebar on the left,
 * scrollable main content area on the right. Standard ERP shell shape.
 */
export function AppShell() {
  return (
    <div className="flex h-screen flex-col">
      <AppBar />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar />
        <main className="flex-1 overflow-y-auto bg-bg-base scrollbar-thin">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
