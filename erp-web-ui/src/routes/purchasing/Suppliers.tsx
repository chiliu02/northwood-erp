import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface Supplier {
  supplierId: string;
  supplierCode: string;
  name: string;
  status: string;
}

/**
 * Supplier master list. Read-only today — onboarding / edit commands land
 * in a future slice when a user story actually needs them. The showcase
 * uses the baseline-seeded suppliers (SUP-001 etc.).
 */
export function Suppliers() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["suppliers"],
    queryFn: () => apiGet<Supplier[]>("/api/suppliers"),
  });

  const columns: Column<Supplier>[] = [
    {
      key: "code",
      header: "Code",
      width: "160px",
      render: (s) => <span className="font-medium tabular-nums">{s.supplierCode}</span>,
    },
    { key: "name", header: "Name", render: (s) => s.name },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (s) => (
        <StatusPill
          label={s.status}
          tone={s.status === "active" ? "success" : "neutral"}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Suppliers"
        description="Supplier master. Tom's view of the vendors that sell us raw material and components."
        trail={[
          { label: "Home", to: "/" },
          { label: "Purchasing" },
          { label: "Suppliers" },
        ]}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load suppliers: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(s) => s.supplierId}
            loading={isLoading}
            emptyState="No suppliers seeded."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} supplier{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}
