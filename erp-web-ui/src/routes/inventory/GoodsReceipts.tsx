import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";

interface GoodsReceipt {
  id: string;
  goodsReceiptNumber: string;
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string | null;
  supplierId: string | null;
  supplierName: string | null;
  warehouseId: string;
  status: string;
  version: number;
}

/**
 * Goods receipts list — Mike's view of inbound stock. Receipts are
 * post-only: each row reflects a posted receipt against a purchase order.
 * Posting a receipt is a backend / CLI action today; this view is
 * observation-only.
 */
export function GoodsReceipts() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["goods-receipts"],
    queryFn: () => apiGet<GoodsReceipt[]>("/api/goods-receipts"),
  });

  const columns: Column<GoodsReceipt>[] = [
    {
      key: "number",
      header: "Receipt #",
      width: "180px",
      render: (g) => <span className="font-medium tabular-nums">{g.goodsReceiptNumber}</span>,
    },
    {
      key: "supplier",
      header: "Supplier",
      render: (g) => g.supplierName ?? "—",
    },
    {
      key: "po",
      header: "Purchase Order",
      render: (g) => (
        <span className="tabular-nums">
          {g.purchaseOrderNumber ?? <span className="text-xs text-text-muted">{g.purchaseOrderHeaderId.slice(0, 8)}…</span>}
        </span>
      ),
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      render: (g) => (
        <StatusPill
          label={g.status}
          tone={g.status === "posted" ? "success" : g.status === "cancelled" ? "error" : "neutral"}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Goods Receipts"
        description="Posted receipts against purchase orders. Each receipt drives a stock_movement and a Dr Inventory / Cr GRNI journal pair."
        trail={[
          { label: "Inventory" },
          { label: "Goods Receipts" },
        ]}
        actions={
          <ActionButton
            variant="primary"
            icon={<Plus className="h-4 w-4" />}
            requiresRole="warehouse_clerk"
            onClick={() => navigate("/goods-receipts/new")}
          >
            Post receipt
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load goods receipts: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(g) => g.id}
            loading={isLoading}
            emptyState="No goods receipts posted yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} receipt{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}
