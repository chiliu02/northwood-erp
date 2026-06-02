import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { DollarSign, Package2, Wrench, Ban } from "lucide-react";
import { apiGet, apiPost, apiPut } from "@/lib/api";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { AuditTab } from "@/components/ui/AuditTab";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { NumberInput, Select } from "@/components/ui/Form";
import { StatusPill } from "@/components/ui/StatusPill";

interface Product {
  productId: string;
  sku: string;
  name: string;
  description: string | null;
  productType: string;
  salesPrice: string;
  standardCost: string;
  reorderPoint: string;
  reorderQuantity: string;
  valuationClass: string | null;
  status: string;
  version: number;
}

interface MaterialsCost {
  productId: string;
  materialsCost: string | null;
  currencyCode: string | null;
  reason: string;
  capturedAt: string;
}

type DialogKind = "pricing" | "reorder" | "make-vs-buy" | "discontinue" | null;

/**
 * Catalog detail page. The four authoring actions Emma drives the
 * showcase with — pricing, reorder policy, make-vs-buy, discontinue —
 * are inline action buttons that open small modals. Each posts to
 * /api/products-cmd/{id}/<action> via the BFF alias to the owning
 * service. No reactivity beyond invalidate-and-refetch.
 */
export function ProductDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DialogKind>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["product", id],
    queryFn: () => apiGet<Product>(`/api/products/${id}`),
    enabled: !!id,
  });

  // materialsCost is owned by manufacturing-service (it's a
  // computed value, not master data). 404 means "never rolled up yet" — the
  // UI renders it the same as inputs_missing.
  const { data: materialsCost } = useQuery<MaterialsCost | null>({
    queryKey: ["product", id, "materials-cost"],
    queryFn: async () => {
      try {
        return await apiGet<MaterialsCost>(`/api/products/${id}/materials-cost`);
      } catch (err) {
        if (err instanceof Error && err.message.includes("404")) return null;
        throw err;
      }
    },
    enabled: !!id,
  });

  const close = () => setDialog(null);

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading product…</div>;
  }
  if (fetchError || !data) {
    return (
      <div className="px-8 py-12">
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load product: {fetchError instanceof Error ? fetchError.message : "Not found"}
        </div>
      </div>
    );
  }

  const isDiscontinued = data.status === "discontinued";

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "Products", to: "/products" },
          { label: data.sku },
        ]}
        title={data.name}
        subtitle={`${data.sku} — ${data.productType.replace(/_/g, " ")}`}
        status={{ label: data.status, tone: isDiscontinued ? "error" : "success" }}
        actions={
          <>
            <ActionButton
              icon={<DollarSign className="h-4 w-4" />}
              onClick={() => setDialog("pricing")}
              requiresRole="catalog_manager"
              disabled={isDiscontinued}
            >
              Change pricing
            </ActionButton>
            <ActionButton
              icon={<Package2 className="h-4 w-4" />}
              onClick={() => setDialog("reorder")}
              requiresRole="catalog_manager"
              disabled={isDiscontinued}
            >
              Reorder policy
            </ActionButton>
            <ActionButton
              icon={<Wrench className="h-4 w-4" />}
              onClick={() => setDialog("make-vs-buy")}
              requiresRole="catalog_manager"
              disabled={isDiscontinued}
            >
              Make vs buy
            </ActionButton>
            <ActionButton
              variant="danger"
              icon={<Ban className="h-4 w-4" />}
              onClick={() => setDialog("discontinue")}
              requiresRole="catalog_manager"
              disabled={isDiscontinued}
            >
              Discontinue
            </ActionButton>
          </>
        }
        tabs={[
          {
            key: "overview",
            label: "Overview",
            content: (
              <div className="grid gap-4 lg:grid-cols-2">
                <FormSection title="Identity">
                  <ReadOnlyField label="SKU" value={<span className="font-medium tabular-nums">{data.sku}</span>} />
                  <ReadOnlyField label="Type" value={data.productType.replace(/_/g, " ")} />
                  <ReadOnlyField label="Name" value={data.name} fullWidth />
                  <ReadOnlyField label="Description" value={data.description || "—"} fullWidth />
                </FormSection>
                <FormSection title="Pricing">
                  <ReadOnlyField label="Sales Price" value={<span className="tabular-nums">{formatMoney(data.salesPrice)}</span>} />
                  <ReadOnlyField label="Standard Cost" value={<span className="tabular-nums">{formatMoney(data.standardCost)}</span>} />
                  <ReadOnlyField
                    label="Materials Cost"
                    value={
                      <div>
                        <span className="tabular-nums">
                          {materialsCost && materialsCost.materialsCost !== null
                            ? `${formatMoney(materialsCost.materialsCost)} ${materialsCost.currencyCode ?? ""}`.trim()
                            : "—"}
                        </span>
                        <span className="ml-2 text-xs text-text-muted">
                          {materialsCostHint(materialsCost ?? null)}
                        </span>
                      </div>
                    }
                  />
                </FormSection>
                <FormSection title="Replenishment">
                  <ReadOnlyField label="Reorder Point" value={<span className="tabular-nums">{formatQty(data.reorderPoint)}</span>} />
                  <ReadOnlyField label="Reorder Qty" value={<span className="tabular-nums">{formatQty(data.reorderQuantity)}</span>} />
                </FormSection>
                <FormSection title="Classification">
                  <ReadOnlyField label="Valuation class" value={data.valuationClass?.replace(/_/g, " ") ?? "—"} />
                  <ReadOnlyField label="Status" value={<StatusPill label={data.status} tone={isDiscontinued ? "error" : "success"} />} />
                </FormSection>
              </div>
            ),
          },
          {
            key: "audit",
            label: "Audit",
            content: <AuditTab aggregateId={id} />,
          },
        ]}
      />

      <PricingDialog
        open={dialog === "pricing"}
        product={data}
        onClose={close}
        onSuccess={() => { close(); queryClient.invalidateQueries({ queryKey: ["product", id] }); queryClient.invalidateQueries({ queryKey: ["products"] }); }}
      />
      <ReorderPolicyDialog
        open={dialog === "reorder"}
        product={data}
        onClose={close}
        onSuccess={() => { close(); queryClient.invalidateQueries({ queryKey: ["product", id] }); queryClient.invalidateQueries({ queryKey: ["products"] }); }}
      />
      <MakeVsBuyDialog
        open={dialog === "make-vs-buy"}
        product={data}
        onClose={close}
        onSuccess={() => { close(); queryClient.invalidateQueries({ queryKey: ["product", id] }); queryClient.invalidateQueries({ queryKey: ["products"] }); }}
      />
      <DiscontinueDialog
        open={dialog === "discontinue"}
        product={data}
        onClose={close}
        onSuccess={() => { close(); queryClient.invalidateQueries({ queryKey: ["product", id] }); queryClient.invalidateQueries({ queryKey: ["products"] }); }}
      />
    </>
  );
}

// ---- Pricing dialog ----

function PricingDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [salesPrice, setSalesPrice] = useState(product.salesPrice);
  const [standardCost, setStandardCost] = useState(product.standardCost);
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      const salesChanged = Number(salesPrice) !== Number(product.salesPrice);
      const costChanged  = Number(standardCost) !== Number(product.standardCost);
      if (salesChanged) {
        await apiPut(`/api/products-cmd/${product.productId}/sales-price`, {
          salesPrice: Number(salesPrice),
          currencyCode: "AUD",
        });
      }
      if (costChanged) {
        await apiPut(`/api/products-cmd/${product.productId}/standard-cost`, {
          standardCost: Number(standardCost),
          currencyCode: "AUD",
        });
      }
    },
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Change pricing"
      message={<>Updates sales price + standard cost on <strong>{product.sku}</strong>. Emits <code>product.SalesPriceChanged</code> and/or <code>product.StandardCostChanged</code>.</>}
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="grid grid-cols-2 gap-3">
          <Field label="Sales Price (AUD)" required>
            <NumberInput min="0" step="0.01" value={salesPrice} onChange={(e) => setSalesPrice(e.target.value)} />
          </Field>
          <Field label="Standard Cost (AUD)" required>
            <NumberInput min="0" step="0.01" value={standardCost} onChange={(e) => setStandardCost(e.target.value)} />
          </Field>
          {error && (
            <div className="col-span-2 rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
              {error}
            </div>
          )}
        </div>
      }
    />
  );
}

// ---- Reorder policy dialog ----

function ReorderPolicyDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [reorderPoint, setReorderPoint] = useState(product.reorderPoint);
  const [reorderQuantity, setReorderQuantity] = useState(product.reorderQuantity);
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/products-cmd/${product.productId}/reorder-policy`, {
      reorderPoint: Number(reorderPoint),
      reorderQuantity: Number(reorderQuantity),
    }),
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Set reorder policy"
      message={<>Updates the replenishment thresholds on <strong>{product.sku}</strong>. Inventory's product_card projection picks this up via <code>product.ReorderPolicyChanged</code>.</>}
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="grid grid-cols-2 gap-3">
          <Field label="Reorder Point" required hint="Triggers reorder when on-hand drops below this.">
            <NumberInput min="0" step="1" value={reorderPoint} onChange={(e) => setReorderPoint(e.target.value)} />
          </Field>
          <Field label="Reorder Quantity" required hint="How many to order.">
            <NumberInput min="0" step="1" value={reorderQuantity} onChange={(e) => setReorderQuantity(e.target.value)} />
          </Field>
          {error && (
            <div className="col-span-2 rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
              {error}
            </div>
          )}
        </div>
      }
    />
  );
}

// ---- Make vs buy dialog ----

function MakeVsBuyDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [mode, setMode] = useState<"manufactured" | "purchased" | "both">("manufactured");
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/products-cmd/${product.productId}/make-vs-buy`, {
      isPurchased: mode === "purchased" || mode === "both",
      isManufactured: mode === "manufactured" || mode === "both",
    }),
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Change make-vs-buy classification"
      message={<>Updates how <strong>{product.sku}</strong> is sourced. Manufacturing's <code>product_card</code> projection updates via <code>product.MakeVsBuyChanged</code>.</>}
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="space-y-3">
          <Field label="Sourcing mode" required>
            <Select value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
              <option value="manufactured">Manufactured internally</option>
              <option value="purchased">Purchased externally</option>
              <option value="both">Both (manufactured or purchased)</option>
            </Select>
          </Field>
          {error && (
            <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
              {error}
            </div>
          )}
        </div>
      }
    />
  );
}

// ---- Discontinue dialog ----

function DiscontinueDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: async () => {
      await apiPost(`/api/products-cmd/${product.productId}/discontinue`);
    },
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Discontinue this product?"
      message={
        <>
          <strong>{product.sku} — {product.name}</strong> will be marked <code>discontinued</code>.
          Sales-service rejects new order lines for this SKU; inventory stops reorder suggestions.
          Idempotent (re-discontinuing is a no-op). No automatic un-discontinue path.
        </>
      }
      confirmLabel="Discontinue"
      variant="danger"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={error ? (
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
          {error}
        </div>
      ) : undefined}
    />
  );
}

interface DialogProps {
  open: boolean;
  product: Product;
  onClose: () => void;
  onSuccess: () => void;
}

function materialsCostHint(mc: MaterialsCost | null): string {
  if (!mc) return "no rollup yet";
  if (mc.reason === "supplier_price_change") return "from preferred supplier";
  if (mc.reason === "inputs_missing") return "no preferred supplier";
  return mc.reason.replace(/_/g, " ");
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function formatQty(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 3 });
}
