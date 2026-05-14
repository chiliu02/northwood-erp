import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { fetchProducts } from "@/api/fetchers";
import { changeProductSalesPrice, changeProductStandardCost, setProductReorderPolicy, discontinueProduct } from "@/api/commands";
import type { ProductRow } from "@/api/types";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { Modal } from "@/components/ui/Modal";
import { Button, FieldRow, FormStatus, Input, type SubmitState } from "@/components/ui/Form";
import { cn, formatMoney, truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

export function Products() {
  const { data, isLoading, error } = useQuery({
    queryKey: ["products"],
    queryFn: fetchProducts,
    refetchInterval: 5_000,
  });
  const p = PERSONAS.emma;

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Products</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: p.accentVar }} aria-hidden />
          {p.name} · {p.role}
        </span>
        <span className="ml-auto text-xs text-text-faint">
          {isLoading ? "loading…" : `${data?.length ?? 0} sku${data?.length === 1 ? "" : "s"}`}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Product master — owned by product-service. Pricing, reorder policy, and discontinue
        flow upstream from here to every other context (Demo 1).
      </p>

      {error && (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            Couldn't reach product-service on :8081
          </p>
          <p className="mt-1 text-text-muted">{String(error)}</p>
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-4 py-2 font-semibold">SKU</th>
              <th className="px-4 py-2 font-semibold">Name</th>
              <th className="px-4 py-2 font-semibold">Type</th>
              <th className="px-4 py-2 text-right font-semibold">Sales price</th>
              <th className="px-4 py-2 text-right font-semibold">Standard cost</th>
              <th className="px-4 py-2 text-right font-semibold">Reorder pt</th>
              <th className="px-4 py-2 text-right font-semibold">Reorder qty</th>
              <th className="px-4 py-2 font-semibold">Status</th>
              <th className="px-4 py-2 font-semibold">Active BOM</th>
              <th className="px-4 py-2 font-semibold"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {(data ?? []).map((p) => (
              <tr key={p.productId} className="hover:bg-bg-hover">
                <td className="px-4 py-2 font-mono">{p.sku}</td>
                <td className="px-4 py-2">{p.name}</td>
                <td className="px-4 py-2">
                  <StatusBadge kind="neutral">{p.productType}</StatusBadge>
                </td>
                <td className="px-4 py-2 text-right tabular-nums">{formatMoney(p.salesPrice)}</td>
                <td className="px-4 py-2 text-right tabular-nums">{formatMoney(p.standardCost)}</td>
                <td className="px-4 py-2 text-right tabular-nums">{p.reorderPoint}</td>
                <td className="px-4 py-2 text-right tabular-nums">{p.reorderQuantity}</td>
                <td className="px-4 py-2">
                  <StatusBadge kind={inferStatusKind(p.status)}>{p.status}</StatusBadge>
                </td>
                <td className="px-4 py-2 font-mono text-xs text-text-faint">
                  {p.activeBomId ? truncateUuid(p.activeBomId) : "—"}
                </td>
                <td className="px-4 py-2 text-right">
                  <ProductEditButton product={p} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!isLoading && (data?.length ?? 0) === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">No products in catalog yet.</p>
        )}
      </div>
    </div>
  );
}

type EditTab = "pricing" | "reorder" | "discontinue";

function ProductEditButton({ product }: { product: ProductRow }) {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<EditTab>("pricing");
  return (
    <>
      <Button variant="ghost" onClick={() => setOpen(true)}>
        <Pencil className="h-3.5 w-3.5" />
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Edit product"
        subtitle={<><span className="font-mono">{product.sku}</span> · {product.name}</>}
      >
        <div className="mb-4 inline-flex rounded-md border border-border-subtle p-0.5">
          <EditTabButton active={tab === "pricing"}      onClick={() => setTab("pricing")}      label="Pricing" />
          <EditTabButton active={tab === "reorder"}      onClick={() => setTab("reorder")}      label="Reorder" />
          <EditTabButton active={tab === "discontinue"}  onClick={() => setTab("discontinue")}  label="Discontinue" />
        </div>
        {tab === "pricing"     && <PricingPanel  product={product} onDone={() => setOpen(false)} />}
        {tab === "reorder"     && <ReorderPanel  product={product} onDone={() => setOpen(false)} />}
        {tab === "discontinue" && <DiscontinuePanel product={product} onDone={() => setOpen(false)} />}
      </Modal>
    </>
  );
}

function EditTabButton({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "rounded px-3 py-1 text-xs font-medium uppercase tracking-wider transition-colors",
        active ? "bg-bg-hover text-text-primary" : "text-text-muted hover:text-text-primary"
      )}
    >
      {label}
    </button>
  );
}

function PricingPanel({ product, onDone }: { product: ProductRow; onDone: () => void }) {
  const [salesPrice, setSalesPrice] = useState(product.salesPrice);
  const [standardCost, setStandardCost] = useState(product.standardCost);
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const queryClient = useQueryClient();

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const salesChanged = Number(salesPrice) !== Number(product.salesPrice);
      const costChanged  = Number(standardCost) !== Number(product.standardCost);
      if (salesChanged) {
        await changeProductSalesPrice(product.productId, { salesPrice, currencyCode });
      }
      if (costChanged) {
        await changeProductStandardCost(product.productId, { standardCost, currencyCode });
      }
      setSubmit({ status: "success", message: "pricing updated" });
      queryClient.invalidateQueries({ queryKey: ["products"] });
      setTimeout(onDone, 600);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <div className="space-y-4">
      <FieldRow label="Sales price" required>
        <Input type="number" step="0.01" min="0" value={salesPrice} onChange={(e) => setSalesPrice(e.target.value)} />
      </FieldRow>
      <FieldRow label="Standard cost" required>
        <Input type="number" step="0.01" min="0" value={standardCost} onChange={(e) => setStandardCost(e.target.value)} />
      </FieldRow>
      <FieldRow label="Currency" required>
        <Input value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value)} />
      </FieldRow>
      <div className="flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button variant="primary" onClick={onSubmit} disabled={submit.status === "submitting"}>
          Save pricing
        </Button>
      </div>
      <p className="text-xs text-text-faint">
        Emits <span className="font-mono">product.SalesPriceChanged</span> and/or <span className="font-mono">product.StandardCostChanged</span>; sales projects the new price for new orders only.
      </p>
    </div>
  );
}

function ReorderPanel({ product, onDone }: { product: ProductRow; onDone: () => void }) {
  const [reorderPoint, setReorderPoint] = useState(product.reorderPoint);
  const [reorderQuantity, setReorderQuantity] = useState(product.reorderQuantity);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const queryClient = useQueryClient();

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      await setProductReorderPolicy(product.productId, { reorderPoint, reorderQuantity });
      setSubmit({ status: "success", message: "reorder policy updated" });
      queryClient.invalidateQueries({ queryKey: ["products"] });
      queryClient.invalidateQueries({ queryKey: ["stock-items"] });
      setTimeout(onDone, 600);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <div className="space-y-4">
      <FieldRow label="Reorder point" required>
        <Input type="number" step="1" min="0" value={reorderPoint} onChange={(e) => setReorderPoint(e.target.value)} />
      </FieldRow>
      <FieldRow label="Reorder quantity" required>
        <Input type="number" step="1" min="0" value={reorderQuantity} onChange={(e) => setReorderQuantity(e.target.value)} />
      </FieldRow>
      <div className="flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button variant="primary" onClick={onSubmit} disabled={submit.status === "submitting"}>
          Save reorder policy
        </Button>
      </div>
      <p className="text-xs text-text-faint">
        Emits <span className="font-mono">product.ReorderPolicyChanged</span>; inventory's
        <span className="font-mono"> stock_item</span> projection picks up the new thresholds within one outbox poll.
      </p>
    </div>
  );
}

function DiscontinuePanel({ product, onDone }: { product: ProductRow; onDone: () => void }) {
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });
  const queryClient = useQueryClient();
  const alreadyDone = product.status === "discontinued";

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      await discontinueProduct(product.productId);
      setSubmit({ status: "success", message: "product discontinued" });
      queryClient.invalidateQueries({ queryKey: ["products"] });
      setTimeout(onDone, 600);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-text-muted">
        Mark this SKU as discontinued. The endpoint is idempotent, so repeating is a no-op.
      </p>
      <p className="text-xs text-text-faint">
        Emits <span className="font-mono">product.ProductDiscontinued</span>. Today no consumer reacts (rejecting new
        order lines / stopping replenishment is in <span className="font-mono">user-stories.md</span> §1.4 ⏳).
      </p>
      <div className="flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="destructive"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || alreadyDone}
        >
          {alreadyDone ? "Already discontinued" : "Discontinue"}
        </Button>
      </div>
    </div>
  );
}
