import clsx from "clsx";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import { useCurrentUser } from "../../lib/UserContext";

type Variant = "primary" | "secondary" | "danger" | "ghost";

interface ActionButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  icon?: ReactNode;
  /**
   * When set, the button consults UserContext and renders
   * disabled with a "Requires role: X" tooltip when the current user lacks
   * the role. While the user is still loading (first paint), the button
   * stays enabled so the page doesn't flash all-disabled before /api/me
   * resolves.
   */
  requiresRole?: string;
}

/**
 * Standard action button. Three variants (primary filled, secondary
 * outlined, danger) plus a ghost variant for inline icon-only actions.
 */
export function ActionButton({
  variant = "secondary",
  icon,
  requiresRole,
  className,
  children,
  type = "button",
  disabled,
  title,
  ...rest
}: ActionButtonProps) {
  const { me, hasRole } = useCurrentUser();
  const userLoaded = me !== null;
  const lacksRole = userLoaded && requiresRole != null && !hasRole(requiresRole);
  const finalDisabled = Boolean(disabled) || lacksRole;
  const finalTitle = lacksRole
    ? `Requires role: ${requiresRole}`
    : title ?? (requiresRole ? `Requires role: ${requiresRole}` : undefined);

  return (
    <button
      {...rest}
      type={type}
      disabled={finalDisabled}
      title={finalTitle}
      aria-disabled={finalDisabled}
      className={clsx(
        "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50",
        VARIANT_CLASSES[variant],
        className
      )}
    >
      {icon && <span className="-ml-0.5 flex h-4 w-4 items-center justify-center">{icon}</span>}
      {children}
    </button>
  );
}

const VARIANT_CLASSES: Record<Variant, string> = {
  primary:
    "bg-brand-primary text-text-on-brand hover:bg-brand-primary-hover",
  secondary:
    "border border-border-default bg-bg-surface text-text-primary hover:bg-bg-subtle hover:border-border-strong",
  danger:
    "border border-status-error/30 bg-bg-surface text-status-error hover:bg-status-error-soft",
  ghost:
    "text-text-secondary hover:bg-bg-subtle hover:text-text-primary",
};
