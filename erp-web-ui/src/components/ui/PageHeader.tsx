import type { ReactNode } from "react";
import { Breadcrumb, type Crumb } from "@/components/layout/Breadcrumb";

interface PageHeaderProps {
  title: string;
  description?: string;
  trail: Crumb[];
  actions?: ReactNode;
}

/**
 * Standard page header for list / form / detail screens. Breadcrumb on
 * top, then title and right-aligned action buttons. Solid white surface
 * with a 1px bottom border separates it from the content area.
 */
export function PageHeader({ title, description, trail, actions }: PageHeaderProps) {
  return (
    <div className="border-b border-border-default bg-bg-surface px-8 py-5">
      <Breadcrumb trail={trail} />
      <div className="mt-2 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-text-primary">{title}</h1>
          {description && <p className="mt-0.5 text-sm text-text-muted">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
