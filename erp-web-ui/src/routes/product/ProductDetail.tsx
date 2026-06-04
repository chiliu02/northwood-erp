import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { DollarSign, Package2, Wrench, Ban, Users, Star, CalendarClock } from "lucide-react";
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
  purchased: boolean;
  manufactured: boolean;
  reorderPoint: string;
  reorderQuantity: string;
  replenishmentStrategy: string | null;
  planningTimeFenceDays: number;
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

interface ApprovedVendor {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  preferred: boolean;
}

interface Supplier {
  supplierId: string;
  supplierCode: string;
  name: string;
  status: string;
}

type DialogKind = "pricing" | "reorder" | "planning-fence" | "make-vs-buy" | "discontinue" | "vendors" | null;

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

  const approvedVendors = useQuery({
    queryKey: ["product", id, "approved-vendors"],
    queryFn: () => apiGet<ApprovedVendor[]>(`/api/products/${id}/approved-vendors`),
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
          { label: "Master Data" },
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
              icon={<CalendarClock className="h-4 w-4" />}
              onClick={() => setDialog("planning-fence")}
              requiresRole="catalog_manager"
              disabled={isDiscontinued}
            >
              Planning fence
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
                  <ReadOnlyField label="Sourcing" value={formatSourcing(data)} />
                  <ReadOnlyField label="Strategy" value={formatStrategy(data.replenishmentStrategy)} />
                  <ReadOnlyField label="Reorder Point" value={<span className="tabular-nums">{formatQty(data.reorderPoint)}</span>} />
                  <ReadOnlyField label="Reorder Qty" value={<span className="tabular-nums">{formatQty(data.reorderQuantity)}</span>} />
                  <ReadOnlyField label="Planning Fence" value={formatFence(data.planningTimeFenceDays)} />
                </FormSection>
                <FormSection title="Classification">
                  <ReadOnlyField label="Valuation class" value={data.valuationClass?.replace(/_/g, " ") ?? "—"} />
                  <ReadOnlyField label="Status" value={<StatusPill label={data.status} tone={isDiscontinued ? "error" : "success"} />} />
                </FormSection>
              </div>
            ),
          },
          {
            key: "vendors",
            label: "Vendors",
            content: (
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <p className="text-sm text-text-secondary">
                    Suppliers approved to supply this product. The preferred vendor drives
                    auto-sourcing on purchase orders and the materials-cost rollup.
                  </p>
                  <ActionButton
                    icon={<Users className="h-4 w-4" />}
                    onClick={() => setDialog("vendors")}
                    requiresRole="catalog_manager"
                    disabled={isDiscontinued}
                  >
                    Edit vendors
                  </ActionButton>
                </div>
                <VendorTable
                  vendors={approvedVendors.data ?? []}
                  loading={approvedVendors.isLoading}
                  purchased={data.purchased}
                />
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
      <PlanningFenceDialog
        open={dialog === "planning-fence"}
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
      <VendorsDialog
        open={dialog === "vendors"}
        product={data}
        current={approvedVendors.data ?? []}
        onClose={close}
        onSuccess={() => { close(); queryClient.invalidateQueries({ queryKey: ["product", id, "approved-vendors"] }); }}
      />
    </>
  );
}

// ---- Vendors table (Vendors tab body) ----

function VendorTable({ vendors, loading, purchased }: { vendors: ApprovedVendor[]; loading: boolean; purchased: boolean }) {
  if (loading) {
    return <div className="text-sm text-text-muted">Loading vendors…</div>;
  }
  if (vendors.length === 0) {
    return (
      <div className="rounded-md border border-border-default bg-bg-surface px-4 py-6 text-sm text-text-muted">
        {purchased
          ? "No approved vendors yet. A purchase requisition for this product can't be auto-sourced — its PO falls back to the default supplier and may come out unpriced. Add a vendor to fix that."
          : "This product is make-only, so approved vendors don't apply. Switch it to purchased (Make vs buy) if it should be bought."}
      </div>
    );
  }
  // Preferred first, then by code.
  const sorted = [...vendors].sort((a, b) =>
    a.preferred === b.preferred ? a.supplierCode.localeCompare(b.supplierCode) : a.preferred ? -1 : 1
  );
  return (
    <div className="overflow-hidden rounded-md border border-border-default">
      <table className="w-full text-sm">
        <thead className="bg-bg-elevated text-xs text-text-secondary">
          <tr>
            <th className="px-3 py-2 text-left font-medium">Supplier</th>
            <th className="px-3 py-2 text-left font-medium" style={{ width: 140 }}>Preferred</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((v) => (
            <tr key={v.supplierId} className="border-t border-border-default">
              <td className="px-3 py-2">
                <span className="font-medium tabular-nums">{v.supplierCode}</span>
                <span className="text-text-secondary"> — {v.supplierName}</span>
              </td>
              <td className="px-3 py-2">
                {v.preferred && (
                  <span className="inline-flex items-center gap-1 text-status-warning">
                    <Star className="h-3.5 w-3.5 fill-current" /> Preferred
                  </span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
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

// ---- Planning time fence dialog ----

function PlanningFenceDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [days, setDays] = useState(String(product.planningTimeFenceDays));
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/products-cmd/${product.productId}/planning-time-fence`, {
      planningTimeFenceDays: Number(days),
    }),
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Set planning time fence"
      message={<>How many days before an order's requested delivery the fulfilment saga starts reserving stock for <strong>{product.sku}</strong>. A far-future order parks at <code>awaiting_release</code> until <em>need-by − fence</em>, then reserves as usual. <code>0</code> = no fence (reserve immediately). Sales picks this up via <code>product.PlanningTimeFenceChanged</code>.</>}
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      body={
        <div className="grid grid-cols-1 gap-3">
          <Field label="Planning time fence (days)" required hint="0 reserves immediately; e.g. 7 holds a far-future order until a week before its requested delivery date.">
            <NumberInput min="0" step="1" value={days} onChange={(e) => setDays(e.target.value)} />
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

// ---- Make vs buy dialog ----

function MakeVsBuyDialog({ open, product, onClose, onSuccess }: DialogProps) {
  const [mode, setMode] = useState<"manufactured" | "purchased" | "both">(
    product.manufactured && product.purchased ? "both" : product.purchased ? "purchased" : "manufactured",
  );
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

// ---- Vendors editor dialog ----

function VendorsDialog({
  open, product, current, onClose, onSuccess,
}: {
  open: boolean;
  product: Product;
  current: ApprovedVendor[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [preferredId, setPreferredId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Active suppliers only — a blocked/inactive supplier can't take a PO.
  const suppliers = useQuery({
    queryKey: ["suppliers"],
    queryFn: () => apiGet<Supplier[]>("/api/suppliers"),
    enabled: open,
  });
  const activeSuppliers = useMemo(
    () => (suppliers.data ?? []).filter((s) => s.status === "active"),
    [suppliers.data]
  );

  // Re-seed local edit state from the saved list each time the dialog opens.
  useEffect(() => {
    if (open) {
      setSelected(new Set(current.map((v) => v.supplierId)));
      setPreferredId(current.find((v) => v.preferred)?.supplierId ?? null);
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function toggle(supplierId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(supplierId)) {
        next.delete(supplierId);
        if (preferredId === supplierId) setPreferredId(null);
      } else {
        next.add(supplierId);
      }
      return next;
    });
  }

  function togglePreferred(supplierId: string) {
    setPreferredId((prev) => (prev === supplierId ? null : supplierId));
    setSelected((prev) => new Set(prev).add(supplierId)); // marking preferred implies approved
  }

  const mutation = useMutation({
    mutationFn: () => {
      const vendors = activeSuppliers
        .filter((s) => selected.has(s.supplierId))
        .map((s) => ({
          supplierId: s.supplierId,
          supplierCode: s.supplierCode,
          supplierName: s.name,
          preferred: s.supplierId === preferredId,
        }));
      return apiPut(`/api/products-cmd/${product.productId}/approved-vendors`, { vendors });
    },
    onSuccess,
    onError: (err) => setError(err instanceof Error ? err.message : "Failed."),
  });

  return (
    <ConfirmDialog
      open={open}
      title="Edit approved vendors"
      message={<>Sets which suppliers may supply <strong>{product.sku}</strong>. Emits <code>product.ApprovedVendorListChanged</code>; purchasing re-projects it for supplier selection.</>}
      confirmLabel="Save"
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={() => mutation.mutate()}
      confirmDisabled={selected.size === 0}
      body={
        <div className="space-y-3">
          {suppliers.isLoading ? (
            <div className="text-sm text-text-muted">Loading suppliers…</div>
          ) : (
            <div className="max-h-72 overflow-y-auto rounded-md border border-border-default divide-y divide-border-default">
              {activeSuppliers.map((s) => {
                const approved = selected.has(s.supplierId);
                return (
                  <div key={s.supplierId} className="flex items-center gap-3 px-3 py-2">
                    <input
                      type="checkbox"
                      checked={approved}
                      onChange={() => toggle(s.supplierId)}
                      className="h-4 w-4 rounded border-border-default"
                    />
                    <div className="flex-1 text-sm">
                      <span className="font-medium tabular-nums">{s.supplierCode}</span>
                      <span className="text-text-secondary"> — {s.name}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => togglePreferred(s.supplierId)}
                      title={preferredId === s.supplierId ? "Preferred supplier" : "Mark preferred"}
                      className={
                        preferredId === s.supplierId
                          ? "inline-flex items-center gap-1 text-xs text-status-warning"
                          : "inline-flex items-center gap-1 text-xs text-text-muted hover:text-text-secondary"
                      }
                    >
                      <Star className={`h-3.5 w-3.5 ${preferredId === s.supplierId ? "fill-current" : ""}`} />
                      Preferred
                    </button>
                  </div>
                );
              })}
            </div>
          )}
          <p className="text-xs text-text-muted">
            At least one vendor is required. The preferred one (optional) is picked first when sourcing a PO.
          </p>
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

/** Make-vs-buy axis. Both flags set = vertically integrated ("or"). */
function formatSourcing(p: { manufactured: boolean; purchased: boolean }): string {
  if (p.manufactured && p.purchased) return "Manufactured or purchased";
  if (p.manufactured) return "Manufactured";
  if (p.purchased) return "Purchased";
  return "—";
}

/** Replenishment-strategy axis — orthogonal to sourcing. */
function formatStrategy(v: string | null | undefined): string {
  if (v == null) return "—";
  if (v === "to_stock") return "To stock";
  if (v === "to_order") return "To order";
  return v.replace(/_/g, " ");
}

function formatFence(days: number | null | undefined): string {
  if (days == null || days <= 0) return "None (reserve immediately)";
  return `${days} day${days === 1 ? "" : "s"}`;
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
