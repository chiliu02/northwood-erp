import { Construction } from "lucide-react";
import { Breadcrumb, type Crumb } from "@/components/layout/Breadcrumb";

interface PlaceholderProps {
  title: string;
  trail?: Crumb[];
}

/**
 * Stub for routes not yet built. C0 ships only the Sales Orders list
 * working end-to-end; everything else renders this placeholder until its
 * sub-slice (C1–C4) lands.
 */
export function Placeholder({ title, trail }: PlaceholderProps) {
  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border-default bg-bg-surface px-8 py-5">
        {trail && <div className="mb-2"><Breadcrumb trail={trail} /></div>}
        <h1 className="text-xl font-semibold text-text-primary">{title}</h1>
      </div>
      <div className="flex flex-1 items-center justify-center px-8 py-12">
        <div className="flex max-w-md flex-col items-center text-center">
          <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-bg-subtle text-text-muted">
            <Construction className="h-6 w-6" />
          </div>
          <h2 className="text-base font-medium text-text-primary">Coming soon</h2>
          <p className="mt-1 text-sm text-text-muted">
            This screen is part of an upcoming slice. Slice C0 ships the Sales
            Orders list as the reference look-and-feel; C1 fills in
            Manufacturing, C2 Finance, C3 Purchasing + Product authoring,
            C4 polish and cross-cutting components.
          </p>
        </div>
      </div>
    </div>
  );
}
