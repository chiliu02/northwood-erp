import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface Customer {
  customerId: string;
  customerCode: string;
  name: string;
  email: string | null;
  phone: string | null;
  billingAddress: string | null;
  shippingAddress: string | null;
  status: string;
  version: number;
}

/** Customer master list. Sarah's screen for finding/creating customers. */
export function Customers() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["customers"],
    queryFn: () => apiGet<Customer[]>("/api/customers"),
  });

  const columns: Column<Customer>[] = [
    {
      key: "code",
      header: "Code",
      width: "160px",
      render: (c) => <span className="font-medium tabular-nums">{c.customerCode}</span>,
    },
    { key: "name", header: "Name", render: (c) => c.name },
    {
      key: "email",
      header: "Email",
      render: (c) => <span className="text-text-muted">{c.email ?? "—"}</span>,
    },
    {
      key: "phone",
      header: "Phone",
      width: "180px",
      render: (c) => <span className="text-text-muted">{c.phone ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (c) => (
        <StatusPill
          label={c.status}
          tone={c.status === "active" ? "success" : c.status === "blocked" ? "error" : "neutral"}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Customers"
        description="Customer master. Sales orders snapshot the name + code at placement; subsequent renames don't ripple back to historical orders."
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "Customers" },
        ]}
        actions={
          <>
            <ActionButton icon={<Filter className="h-4 w-4" />}>Filter</ActionButton>
            <ActionButton icon={<Download className="h-4 w-4" />}>Export</ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="sales_clerk"
              onClick={() => navigate("/customers/new")}
            >
              New Customer
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load customers: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(c) => c.customerId}
            onRowClick={(c) => navigate(`/customers/${c.customerId}`)}
            loading={isLoading}
            emptyState="No customers yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} customer{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}
