import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { downloadCsv } from "@/lib/csv";
import { StatusPill } from "@/components/ui/StatusPill";

interface Product {
  productId: string;
  sku: string;
  name: string;
  description: string | null;
  productType: string;
  salesPrice: string;
  standardCost: string;
  purchased: boolean;
  manufactured: boolean;
  reorderPoint: string;
  reorderQuantity: string;
  replenishmentStrategy: string | null;
  valuationClass: string | null;
  status: string;
  version: number;
}

/** Catalog list — Emma's primary screen. */
export function Products() {
  const navigate = useNavigate();
  const { data, isLoading, error } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const filterFields: FilterField<Product>[] = [
    { key: "sku", label: "SKU", get: (p) => p.sku },
    { key: "name", label: "Name", get: (p) => p.name },
    { key: "type", label: "Type", type: "select", get: (p) => p.productType, optionLabel: (v) => v.replace(/_/g, " ") },
    { key: "sourcing", label: "Sourcing", type: "select", get: sourcingToken, optionLabel: sourcingLabel },
    { key: "replenishment", label: "Replenishment", type: "select", get: (p) => p.replenishmentStrategy ?? "", optionLabel: formatStrategy },
    { key: "status", label: "Status", type: "select", get: (p) => p.status },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<Product>[] = [
    {
      key: "sku",
      header: "SKU",
      width: "220px",
      sortAccessor: (p) => p.sku,
      render: (p) => <span className="whitespace-nowrap font-medium tabular-nums">{p.sku}</span>,
    },
    { key: "name", header: "Name", sortAccessor: (p) => p.name, render: (p) => p.name },
    {
      key: "type",
      header: "Type",
      width: "150px",
      sortAccessor: (p) => p.productType,
      render: (p) => <span className="text-text-muted">{p.productType.replace(/_/g, " ")}</span>,
    },
    {
      key: "salesPrice",
      header: "Sales Price",
      numeric: true,
      width: "120px",
      sortAccessor: (p) => Number(p.salesPrice),
      render: (p) => formatMoney(p.salesPrice),
    },
    {
      key: "standardCost",
      header: "Std Cost",
      numeric: true,
      width: "120px",
      sortAccessor: (p) => Number(p.standardCost),
      render: (p) => formatMoney(p.standardCost),
    },
    {
      key: "sourcing",
      header: "Sourcing",
      width: "150px",
      sortAccessor: (p) => formatSourcing(p),
      render: (p) => <span className="text-text-muted">{formatSourcing(p)}</span>,
    },
    {
      key: "replenishment",
      header: "Replenishment",
      width: "130px",
      sortAccessor: (p) => p.replenishmentStrategy ?? "",
      render: (p) => <span className="text-text-muted">{formatStrategy(p.replenishmentStrategy)}</span>,
    },
    {
      key: "reorder",
      header: "Reorder pt / qty",
      numeric: true,
      width: "140px",
      sortAccessor: (p) => Number(p.reorderPoint),
      render: (p) => (
        <span className="text-text-muted">
          {formatQty(p.reorderPoint)} / {formatQty(p.reorderQuantity)}
        </span>
      ),
    },
    {
      key: "valuation",
      header: "Valuation",
      width: "140px",
      sortAccessor: (p) => p.valuationClass ?? "",
      render: (p) => (
        <span className="text-text-muted">{p.valuationClass?.replace(/_/g, " ") ?? "—"}</span>
      ),
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      sortAccessor: (p) => p.status,
      render: (p) => <StatusPill label={p.status} tone={p.status === "discontinued" ? "error" : "success"} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Products"
        description="Catalog of every product. Pricing, reorder policy, make-vs-buy, and discontinue are authored on the detail page."
        trail={[
          { label: "Master Data" },
          { label: "Products" },
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
              onClick={() => downloadCsv("products.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="catalog_manager"
              onClick={() => navigate("/products/new")}
            >
              New Product
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
            Failed to load products: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(p) => p.productId}
            onRowClick={(p) => navigate(`/products/${p.productId}`)}
            loading={isLoading}
            emptyState={filter.active ? "No products match the filter." : "No products in the catalog."}
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} product{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>
    </>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}

/** Make-vs-buy axis. Both flags set = vertically integrated ("or"). */
function formatSourcing(p: { manufactured: boolean; purchased: boolean }): string {
  if (p.manufactured && p.purchased) return "Manufactured or purchased";
  if (p.manufactured) return "Manufactured";
  if (p.purchased) return "Purchased";
  return "—";
}

/** Canonical sourcing token for the filter (matches + dropdown options). */
function sourcingToken(p: { manufactured: boolean; purchased: boolean }): string {
  if (p.manufactured && p.purchased) return "both";
  if (p.manufactured) return "manufactured";
  if (p.purchased) return "purchased";
  return "";
}

function sourcingLabel(token: string): string {
  if (token === "both") return "Manufactured or purchased";
  if (token === "manufactured") return "Manufactured";
  if (token === "purchased") return "Purchased";
  return token;
}

/** Replenishment-strategy axis — orthogonal to sourcing. */
function formatStrategy(v: string | null | undefined): string {
  if (v == null) return "—";
  if (v === "to_stock") return "To stock";
  if (v === "to_order") return "To order";
  return v.replace(/_/g, " ");
}
