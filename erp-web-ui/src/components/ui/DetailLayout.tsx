import clsx from "clsx";
import type { ReactNode } from "react";
import { useState } from "react";
import { Breadcrumb, type Crumb } from "@/components/layout/Breadcrumb";
import { StatusPill } from "./StatusPill";

interface Tab {
  key: string;
  label: string;
  badge?: string | number;
  content: ReactNode;
}

interface DetailLayoutProps {
  trail: Crumb[];
  title: string;
  subtitle?: string;
  status?: { label: string; tone?: "info" | "success" | "warn" | "error" | "neutral" };
  /** Optional secondary pill rendered next to the status (e.g. a cancellation marker). */
  secondaryStatus?: { label: string; tone?: "info" | "success" | "warn" | "error" | "neutral" } | null;
  actions?: ReactNode;
  tabs?: Tab[];
  /** When tabs are absent, this content renders directly under the header. */
  children?: ReactNode;
}

/**
 * Standard ERP detail-page layout. Header strip with breadcrumb, title,
 * status pill, action buttons; then either a tab strip (for complex
 * aggregates) or freeform content. Mirrors the SAP Fiori detail-page
 * shape.
 */
export function DetailLayout({
  trail,
  title,
  subtitle,
  status,
  secondaryStatus,
  actions,
  tabs,
  children,
}: DetailLayoutProps) {
  const [activeTabKey, setActiveTabKey] = useState(tabs?.[0]?.key);
  const activeTab = tabs?.find((t) => t.key === activeTabKey) ?? tabs?.[0];

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border-default bg-bg-surface">
        <div className="px-8 pt-5">
          <Breadcrumb trail={trail} />
          <div className="mt-2 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <h1 className="text-xl font-semibold text-text-primary">{title}</h1>
              {status && <StatusPill label={status.label} tone={status.tone} />}
              {secondaryStatus && <StatusPill label={secondaryStatus.label} tone={secondaryStatus.tone} />}
            </div>
            {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
          </div>
          {subtitle && <p className="mt-1 text-sm text-text-muted">{subtitle}</p>}
        </div>

        {tabs && tabs.length > 0 && (
          <div className="mt-4 flex gap-1 px-8">
            {tabs.map((tab) => {
              const active = tab.key === activeTab?.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => setActiveTabKey(tab.key)}
                  className={clsx(
                    "border-b-2 px-3 py-2 text-sm transition",
                    active
                      ? "border-brand-primary font-medium text-brand-primary"
                      : "border-transparent text-text-secondary hover:text-text-primary"
                  )}
                >
                  {tab.label}
                  {tab.badge !== undefined && tab.badge !== null && (
                    <span
                      className={clsx(
                        "ml-2 rounded-full px-1.5 py-0.5 text-[10px] tabular-nums",
                        active ? "bg-brand-primary text-text-on-brand" : "bg-bg-subtle text-text-muted"
                      )}
                    >
                      {tab.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-8 py-6 scrollbar-thin">
        {tabs ? activeTab?.content : children}
      </div>
    </div>
  );
}
