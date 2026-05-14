import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight, ChevronDown, Boxes, Layers, Wrench } from "lucide-react";
import { apiGet, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { Select } from "@/components/ui/Form";

interface Product {
  productId: string;
  sku: string;
  name: string;
  productType: string;
  status: string;
}

interface BomNode {
  componentProductId: string;
  componentSku: string;
  componentName: string;
  componentKind: string;
  quantityPerFinishedUnit: string;
  scrapFactorPercent: string;
  subBomHeaderId: string | null;
  children: BomNode[];
}

interface BomTree {
  bomHeaderId: string;
  productId: string;
  productSku: string;
  productName: string;
  components: BomNode[];
}

/**
 * Read-only BOM tree viewer. Pick a finished or semi-finished product
 * from the dropdown; the recursive BOM (sub-assemblies expanded inline)
 * renders on the right. Backed by GET /api/boms/by-product/{id}.
 */
export function Boms() {
  const [productId, setProductId] = useState("");

  const { data: products, isLoading: productsLoading } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const manufacturable = useMemo(
    () =>
      (products ?? [])
        .filter((p) => p.productType === "finished_good" || p.productType === "semi_finished_good")
        .filter((p) => p.status === "active")
        .sort((a, b) => a.sku.localeCompare(b.sku)),
    [products]
  );

  const { data: tree, isLoading: treeLoading, error: treeError } = useQuery({
    queryKey: ["bom-tree", productId],
    queryFn: () => apiGet<BomTree>(`/api/boms/by-product/${productId}`),
    enabled: !!productId,
    retry: false,
  });

  const notFound = treeError instanceof ApiError && treeError.status === 404;

  return (
    <>
      <PageHeader
        title="Bills of Materials"
        description="Read-only tree view of the active BOM for a finished or semi-finished product. Sub-assemblies expand inline."
        trail={[
          { label: "Home", to: "/" },
          { label: "Manufacturing" },
          { label: "BOMs" },
        ]}
      />
      <div className="space-y-6 px-8 py-6">
        <div className="max-w-xl">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wider text-text-muted">
            Manufactured product
          </label>
          <Select
            value={productId}
            onChange={(e) => setProductId(e.target.value)}
            disabled={productsLoading}
          >
            <option value="">— pick a product —</option>
            {manufacturable.map((p) => (
              <option key={p.productId} value={p.productId}>
                {p.sku} · {p.name}
              </option>
            ))}
          </Select>
          {productsLoading && (
            <p className="mt-1 text-xs text-text-muted">Loading product list…</p>
          )}
        </div>

        {!productId ? (
          <div className="rounded-md border border-dashed border-border-default px-4 py-8 text-center text-sm text-text-muted">
            Pick a manufactured product to view its active BOM tree.
          </div>
        ) : treeLoading ? (
          <div className="rounded-md border border-border-default bg-bg-surface px-4 py-6 text-center text-sm text-text-muted">
            Loading BOM…
          </div>
        ) : notFound ? (
          <div className="rounded-md border border-status-warn/30 bg-status-warn-soft px-4 py-3 text-sm text-status-warn">
            No active BOM on file for this product.
          </div>
        ) : treeError ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load BOM: {(treeError as Error).message}
          </div>
        ) : tree ? (
          <BomTreeBlock tree={tree} />
        ) : null}
      </div>
    </>
  );
}

function BomTreeBlock({ tree }: { tree: BomTree }) {
  return (
    <div className="rounded-md border border-border-default bg-bg-surface">
      <header className="flex items-center justify-between border-b border-border-default px-4 py-3">
        <div className="flex items-center gap-2">
          <Boxes className="h-4 w-4 text-brand-primary" />
          <div>
            <div className="text-sm font-semibold text-text-primary">
              {tree.productSku} · {tree.productName}
            </div>
            <div className="font-mono text-[11px] text-text-muted">
              bom_header_id {shortUuid(tree.bomHeaderId)}
            </div>
          </div>
        </div>
        <span className="text-xs text-text-muted">{countNodes(tree.components)} components</span>
      </header>
      <div className="px-4 py-3 text-sm">
        {tree.components.length === 0 ? (
          <p className="text-text-muted">This BOM has no lines.</p>
        ) : (
          <ul className="space-y-1">
            {tree.components.map((c) => (
              <BomTreeRow key={c.componentProductId} node={c} depth={0} />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function BomTreeRow({ node, depth }: { node: BomNode; depth: number }) {
  const hasChildren = node.children.length > 0;
  const [open, setOpen] = useState(true);

  return (
    <li>
      <div
        className="flex items-center gap-2 rounded px-2 py-1 hover:bg-bg-subtle"
        style={{ paddingLeft: `${depth * 16 + 8}px` }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="flex h-5 w-5 items-center justify-center rounded text-text-muted hover:bg-bg-subtle hover:text-text-primary"
            aria-label={open ? "Collapse" : "Expand"}
          >
            {open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
          </button>
        ) : (
          <span className="inline-block h-5 w-5" aria-hidden />
        )}
        <ComponentKindIcon kind={node.componentKind} />
        <div className="flex flex-1 items-center justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="truncate">
              <span className="font-mono text-xs font-medium text-text-primary">{node.componentSku}</span>
              <span className="ml-2 text-text-muted">{node.componentName}</span>
            </div>
            <div className="text-[11px] text-text-muted">
              kind: <span className="font-medium">{node.componentKind}</span>
              {node.subBomHeaderId && (
                <>
                  {" · "}
                  <span className="font-mono">sub-bom {shortUuid(node.subBomHeaderId)}</span>
                </>
              )}
            </div>
          </div>
          <div className="flex shrink-0 items-baseline gap-3 text-xs tabular-nums">
            <span className="text-text-primary">
              <strong>{formatQty(node.quantityPerFinishedUnit)}</strong>
              <span className="ml-1 text-[10px] text-text-muted">per unit</span>
            </span>
            {Number(node.scrapFactorPercent) > 0 && (
              <span className="text-status-warn">+{node.scrapFactorPercent}% scrap</span>
            )}
          </div>
        </div>
      </div>
      {hasChildren && open && (
        <ul className="space-y-1">
          {node.children.map((c) => (
            <BomTreeRow key={c.componentProductId} node={c} depth={depth + 1} />
          ))}
        </ul>
      )}
    </li>
  );
}

function ComponentKindIcon({ kind }: { kind: string }) {
  if (kind === "sub_assembly" || kind === "semi_finished") {
    return <Layers className="h-3.5 w-3.5 shrink-0 text-brand-primary" aria-label="sub-assembly" />;
  }
  return <Wrench className="h-3.5 w-3.5 shrink-0 text-text-muted" aria-label="raw material" />;
}

function countNodes(nodes: BomNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countNodes(n.children), 0);
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { maximumFractionDigits: 4 });
}

function shortUuid(uuid: string): string {
  return uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-4)}` : "—";
}
