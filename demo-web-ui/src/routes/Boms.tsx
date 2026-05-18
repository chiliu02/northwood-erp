import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight, ChevronDown, Boxes, Layers, Wrench, ListTree, List } from "lucide-react";
import { fetchProducts, fetchBomTree, fetchBomFlat, NotFoundError } from "@/api/fetchers";
import type { BomFlatComponent, BomNode, BomTree, ProductRow } from "@/api/types";
import { Select } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";
import { truncateUuid } from "@/lib/utils";

type ViewMode = "tree" | "flat";

/**
 * Read-only BOM tree viewer. Pick a finished or semi-finished product
 * from the dropdown; the recursive BOM (sub-assemblies expanded inline)
 * renders below. Backed by GET /api/boms/by-product/{id}.
 */
export function Boms() {
  const p = PERSONAS.emma;
  const [productId, setProductId] = useState("");
  const [viewMode, setViewMode] = useState<ViewMode>("tree");

  const { data: products, isLoading: productsLoading } = useQuery({
    queryKey: ["products"],
    queryFn: fetchProducts,
  });

  const manufacturable = useMemo(
    () =>
      (products ?? [])
        .filter((row) => row.productType === "finished_good" || row.productType === "semi_finished_good")
        .filter((row) => row.status === "active")
        .sort((a, b) => a.sku.localeCompare(b.sku)),
    [products]
  );

  const { data: tree, isLoading: treeLoading, error: treeError } = useQuery({
    queryKey: ["bom-tree", productId],
    queryFn: () => fetchBomTree(productId),
    enabled: !!productId && viewMode === "tree",
    retry: false,
  });

  const { data: flat, isLoading: flatLoading, error: flatError } = useQuery({
    queryKey: ["bom-flat", productId],
    queryFn: () => fetchBomFlat(productId),
    enabled: !!productId && viewMode === "flat",
    retry: false,
  });

  const activeError = viewMode === "tree" ? treeError : flatError;
  const activeLoading = viewMode === "tree" ? treeLoading : flatLoading;
  const notFound = activeError instanceof NotFoundError;
  // Empty flat result is the same signal as a 404 tree — root product has no
  // active BOM. The endpoint returns 200 + [] (not 404), so detect it here.
  const flatEmpty = viewMode === "flat" && !flatError && flat && flat.length === 0;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">BOMs</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: p.accentVar }} aria-hidden />
          {p.name} · {p.role}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Read-only tree view of the active Bill of Materials for a finished or semi-finished
        product. Sub-assemblies expand inline. Authoring (create draft / add lines / activate)
        is via REST today — full editor UI deferred per dev-todo §3.4.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-0 flex-1 max-w-xl">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wider text-text-muted">
            Manufactured product
          </label>
          <Select
            value={productId}
            onChange={(e) => setProductId(e.target.value)}
            disabled={productsLoading}
          >
            <option value="">— pick a product —</option>
            {manufacturable.map((row) => (
              <option key={row.productId} value={row.productId}>
                {row.sku} · {row.name}
              </option>
            ))}
          </Select>
          {productsLoading && (
            <p className="mt-1 text-xs text-text-muted">Loading product list…</p>
          )}
        </div>
        <ViewModeToggle value={viewMode} onChange={setViewMode} />
      </div>

      {!productId ? (
        <div className="rounded-md border border-dashed border-border-default px-4 py-8 text-center text-sm text-text-muted">
          Pick a manufactured product to view its active BOM tree.
        </div>
      ) : activeLoading ? (
        <div className="rounded-md border border-border-default bg-bg-surface px-4 py-6 text-center text-sm text-text-muted">
          Loading BOM…
        </div>
      ) : notFound || flatEmpty ? (
        <NoActiveBom productId={productId} products={products ?? []} />
      ) : activeError ? (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            Failed to load BOM
          </p>
          <p className="mt-1 text-text-muted">{String(activeError)}</p>
        </div>
      ) : viewMode === "tree" && tree ? (
        <BomTreeBlock tree={tree} />
      ) : viewMode === "flat" && flat ? (
        <BomFlatBlock components={flat} />
      ) : null}
    </div>
  );
}

function ViewModeToggle({ value, onChange }: { value: ViewMode; onChange: (v: ViewMode) => void }) {
  return (
    <div
      role="radiogroup"
      aria-label="BOM view"
      className="inline-flex rounded-md border border-border-default bg-bg-surface p-0.5"
    >
      <ToggleOption
        active={value === "tree"}
        onClick={() => onChange("tree")}
        icon={<ListTree className="h-3.5 w-3.5" />}
        label="Tree"
      />
      <ToggleOption
        active={value === "flat"}
        onClick={() => onChange("flat")}
        icon={<List className="h-3.5 w-3.5" />}
        label="Flat"
      />
    </div>
  );
}

function ToggleOption({
  active, onClick, icon, label,
}: { active: boolean; onClick: () => void; icon: React.ReactNode; label: string }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={active}
      onClick={onClick}
      className={
        "flex items-center gap-1.5 rounded px-3 py-1.5 text-xs font-medium transition-colors " +
        (active
          ? "bg-bg-elevated text-text-primary shadow-sm"
          : "text-text-muted hover:text-text-primary")
      }
    >
      {icon}
      {label}
    </button>
  );
}

function NoActiveBom({ productId, products }: { productId: string; products: ProductRow[] }) {
  const product = products.find((p) => p.productId === productId);
  return (
    <div
      className="rounded-md border px-4 py-3 text-sm"
      style={{
        borderColor: "var(--color-state-warn)",
        background: "color-mix(in srgb, var(--color-state-warn) 12%, transparent)",
        color: "var(--color-state-warn)",
      }}
    >
      No active BOM on file for {product ? `${product.sku} · ${product.name}` : "this product"}.
    </div>
  );
}

function BomTreeBlock({ tree }: { tree: BomTree }) {
  return (
    <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
      <header className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
        <div className="flex items-center gap-2">
          <Boxes className="h-4 w-4 text-text-muted" />
          <div>
            <div className="text-sm font-semibold text-text-primary">
              <span className="font-mono">{tree.productSku}</span>
              <span className="ml-2 text-text-muted">{tree.productName}</span>
            </div>
            <div className="font-mono text-[11px] text-text-faint">
              bom_header_id {truncateUuid(tree.bomHeaderId)}
            </div>
          </div>
        </div>
        <span className="text-xs text-text-muted">
          {countNodes(tree.components)} component{countNodes(tree.components) === 1 ? "" : "s"}
        </span>
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
        className="flex items-center gap-2 rounded px-2 py-1 hover:bg-bg-hover"
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
            <div className="text-[11px] text-text-faint">
              kind: <span className="font-medium">{node.componentKind}</span>
              {node.subBomHeaderId && (
                <>
                  {" · "}
                  <span className="font-mono">sub-bom {truncateUuid(node.subBomHeaderId)}</span>
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
              <span style={{ color: "var(--color-state-warn)" }}>+{node.scrapFactorPercent}% scrap</span>
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

function BomFlatBlock({ components }: { components: BomFlatComponent[] }) {
  return (
    <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
      <header className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
        <div className="flex items-center gap-2">
          <Boxes className="h-4 w-4 text-text-muted" />
          <div className="text-sm font-semibold text-text-primary">
            Flat component list
          </div>
        </div>
        <span className="text-xs text-text-muted">
          {components.length} entr{components.length === 1 ? "y" : "ies"}
          <span className="ml-2 text-text-faint">· quantities multiplied through depth</span>
        </span>
      </header>
      <div className="px-4 py-3 text-sm">
        <ul className="space-y-1">
          {components.map((c, i) => (
            <li
              key={`${c.componentProductId}-${i}`}
              className="flex items-center gap-2 rounded px-2 py-1 hover:bg-bg-hover"
              style={{ paddingLeft: `${(c.depth - 1) * 16 + 8}px` }}
            >
              <ComponentKindIcon kind={c.componentKind} />
              <div className="flex flex-1 items-center justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate">
                    <span className="font-mono text-xs font-medium text-text-primary">{c.componentSku}</span>
                    <span className="ml-2 text-text-muted">{c.componentName}</span>
                  </div>
                  <div className="text-[11px] text-text-faint">
                    kind: <span className="font-medium">{c.componentKind}</span>
                    {" · "}
                    depth <span className="font-medium">{c.depth}</span>
                  </div>
                </div>
                <div className="flex shrink-0 items-baseline gap-3 text-xs tabular-nums">
                  <span className="text-text-primary">
                    <strong>{formatQty(c.cumulativeQuantityPerFinishedUnit)}</strong>
                    <span className="ml-1 text-[10px] text-text-muted">per finished unit</span>
                  </span>
                </div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function ComponentKindIcon({ kind }: { kind: string }) {
  if (kind === "sub_assembly" || kind === "semi_finished") {
    return <Layers className="h-3.5 w-3.5 shrink-0 text-text-muted" aria-label="sub-assembly" />;
  }
  return <Wrench className="h-3.5 w-3.5 shrink-0 text-text-faint" aria-label="raw material" />;
}

function countNodes(nodes: BomNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countNodes(n.children), 0);
}

function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { maximumFractionDigits: 4 });
}
