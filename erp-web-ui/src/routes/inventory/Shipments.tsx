import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface Shipment {
  id: string;
  shipmentNumber: string;
  salesOrderHeaderId: string;
  customerId: string | null;
  customerName: string | null;
  warehouseId: string;
  status: string;
  version: number;
}

/**
 * Shipments list — Mike's view of outbound stock. Shipments are post-only:
 * each row reflects a posted shipment against a sales order. Posting drives
 * a Dr COGS / Cr Inventory journal pair plus the customer-invoice auto-create.
 */
export function Shipments() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["shipments"],
    queryFn: () => apiGet<Shipment[]>("/api/shipments"),
  });

  const columns: Column<Shipment>[] = [
    {
      key: "number",
      header: "Shipment #",
      width: "180px",
      render: (s) => <span className="font-medium tabular-nums">{s.shipmentNumber}</span>,
    },
    {
      key: "customer",
      header: "Customer",
      render: (s) => s.customerName ?? "—",
    },
    {
      key: "salesOrder",
      header: "Sales Order",
      render: (s) => <span className="tabular-nums text-xs">{s.salesOrderHeaderId.slice(0, 8)}…</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (s) => (
        <StatusPill
          label={s.status}
          tone={s.status === "posted" ? "success" : s.status === "cancelled" ? "error" : "neutral"}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Shipments"
        description="Posted shipments against sales orders. Each shipment drives a stock_movement, a Dr COGS / Cr Inventory journal, and the auto-created customer invoice."
        trail={[
          { label: "Inventory" },
          { label: "Shipments" },
        ]}
        actions={
          <ActionButton
            variant="primary"
            icon={<Plus className="h-4 w-4" />}
            requiresRole="warehouse_clerk"
            onClick={() => navigate("/shipments/new")}
          >
            Post shipment
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load shipments: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(s) => s.id}
            loading={isLoading}
            emptyState="No shipments posted yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} shipment{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}
