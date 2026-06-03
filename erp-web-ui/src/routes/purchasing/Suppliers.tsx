import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { StatusPill } from "@/components/ui/StatusPill";
import { downloadCsv } from "@/lib/csv";

interface Supplier {
  supplierId: string;
  supplierCode: string;
  name: string;
  email: string | null;
  phone: string | null;
  address: string | null;
  status: string;
  version: number;
}

function statusTone(status: string): "success" | "neutral" | "error" {
  if (status === "active") return "success";
  if (status === "blocked") return "error";
  return "neutral";
}

/**
 * Supplier master list. Filterable + exportable like the other list pages;
 * "New supplier" onboards one and clicking a row opens the detail page where
 * details + status are edited (mirrors the Customers screen).
 */
export function Suppliers() {
  const navigate = useNavigate();

  const { data, isLoading, error } = useQuery({
    queryKey: ["suppliers"],
    queryFn: () => apiGet<Supplier[]>("/api/suppliers"),
  });

  const filterFields: FilterField<Supplier>[] = [
    { key: "code", label: "Code", get: (s) => s.supplierCode },
    { key: "name", label: "Name", get: (s) => s.name },
    { key: "email", label: "Email", get: (s) => s.email ?? "" },
    { key: "status", label: "Status", type: "select", get: (s) => s.status },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<Supplier>[] = [
    {
      key: "code",
      header: "Code",
      width: "150px",
      sortAccessor: (s) => s.supplierCode,
      render: (s) => <span className="font-medium tabular-nums">{s.supplierCode}</span>,
    },
    { key: "name", header: "Name", sortAccessor: (s) => s.name, render: (s) => s.name },
    {
      key: "email",
      header: "Email",
      sortAccessor: (s) => s.email ?? "",
      render: (s) => <span className="text-text-muted">{s.email ?? "—"}</span>,
    },
    {
      key: "phone",
      header: "Phone",
      width: "170px",
      sortAccessor: (s) => s.phone ?? "",
      render: (s) => <span className="text-text-muted">{s.phone ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      sortAccessor: (s) => s.status,
      render: (s) => <StatusPill label={s.status} tone={statusTone(s.status)} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Suppliers"
        description="Supplier master. Onboard vendors, edit their details, and change status (active / inactive / blocked). A blocked or inactive supplier is skipped by PO supplier-selection."
        trail={[
          { label: "Home", to: "/" },
          { label: "Purchasing" },
          { label: "Suppliers" },
        ]}
        actions={
          <>
            <ActionButton
              icon={<Filter className="h-4 w-4" />}
              variant={filter.open ? "primary" : "secondary"}
              onClick={filter.toggle}
            >
              Filter
            </ActionButton>
            <ActionButton
              icon={<Download className="h-4 w-4" />}
              onClick={() => downloadCsv("suppliers.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="purchasing_manager"
              onClick={() => navigate("/suppliers/new")}
            >
              New supplier
            </ActionButton>
          </>
        }
      />

      <FilterPanel
        open={filter.open}
        rows={data ?? []}
        fields={filterFields}
        values={filter.values}
        onChange={filter.set}
        onClear={filter.clear}
        onClose={filter.close}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load suppliers: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(s) => s.supplierId}
            onRowClick={(s) => navigate(`/suppliers/${s.supplierId}`)}
            loading={isLoading}
            emptyState={filter.active ? "No suppliers match the filter." : "No suppliers yet. Click 'New supplier' to add one."}
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} supplier{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>
    </>
  );
}
