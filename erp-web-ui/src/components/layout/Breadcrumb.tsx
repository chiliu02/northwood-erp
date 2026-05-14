import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";

export interface Crumb {
  label: string;
  to?: string;        // omit on the last (current) crumb
}

/**
 * Standard ERP breadcrumb: Home › Module › Page › Detail.
 * Caller passes the trail; the component handles separators + the
 * "current page" styling on the last entry.
 */
export function Breadcrumb({ trail }: { trail: Crumb[] }) {
  return (
    <nav aria-label="Breadcrumb" className="flex items-center gap-1 text-xs text-text-muted">
      {trail.map((c, i) => {
        const last = i === trail.length - 1;
        return (
          <span key={i} className="flex items-center gap-1">
            {i > 0 && <ChevronRight className="h-3 w-3 text-text-faint" />}
            {!last && c.to ? (
              <Link to={c.to} className="hover:text-text-secondary">
                {c.label}
              </Link>
            ) : (
              <span className={last ? "text-text-secondary" : ""}>{c.label}</span>
            )}
          </span>
        );
      })}
    </nav>
  );
}
