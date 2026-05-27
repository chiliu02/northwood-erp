import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { SlidersHorizontal } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";

interface StockItem {
  stockItemId: string;
  productId: string;
  productSku: string;
  productName: string;
  productType: string;
  baseUomCode: string;
  trackingMode: string;
  reorderPoint: string | null;
  reorderQuantity: string | null;
  version: number;
}

/**
 * Stock item master — Mike's catalogue. Reorder policy lives on Product
 * (Shape A); inventory projects it. The list is read-only here; thresholds
 * are edited via Product Detail.
 */
export function StockItems() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["stock-items"],
    queryFn: () => apiGet<StockItem[]>("/api/stock-items"),
  });

  const columns: Column<StockItem>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "180px",
      render: (s) => <span className="font-medium tabular-nums">{s.productSku}</span>,
    },
    { key: "name", header: "Name", render: (s) => s.productName },
    {
      key: "type",
      header: "Type",
      width: "160px",
      render: (s) => <span className="text-text-muted">{s.productType.replace(/_/g, " ")}</span>,
    },
    {
      key: "uom",
      header: "UoM",
      width: "80px",
      render: (s) => <span className="text-text-muted tabular-nums">{s.baseUomCode}</span>,
    },
    {
      key: "tracking",
      header: "Tracking",
      width: "120px",
      render: (s) => <span className="text-text-muted">{s.trackingMode}</span>,
    },
    {
      key: "reorderPoint",
      header: "Reorder Pt",
      width: "120px",
      numeric: true,
      render: (s) => <span className="tabular-nums">{formatQty(s.reorderPoint)}</span>,
    },
    {
      key: "reorderQty",
      header: "Reorder Qty",
      width: "120px",
      numeric: true,
      render: (s) => <span className="tabular-nums">{formatQty(s.reorderQuantity)}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Stock Balances"
        description="Inventory's projection of every catalogued product. Reorder policy is owned by product-service and projected here via product.ReorderPolicyChanged."
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Stock Balances" },
        ]}
        actions={
          <ActionButton
            variant="primary"
            icon={<SlidersHorizontal className="h-4 w-4" />}
            requiresRole="warehouse_manager"
            onClick={() => navigate("/stock-adjustments/new")}
          >
            Adjust stock
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load stock items: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={data ?? []}
            rowKey={(s) => s.stockItemId}
            loading={isLoading}
            emptyState="No stock items projected yet."
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {data.length} item{data.length === 1 ? "" : "s"}.
          </div>
        )}
      </div>
    </>
  );
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
