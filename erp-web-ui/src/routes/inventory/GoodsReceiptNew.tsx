import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";

const DEFAULT_WAREHOUSE = "MAIN";

// PO statuses that are receivable. The backend ultimately enforces; this just
// scopes the picker.
const RECEIVABLE_STATUSES = new Set(["sent", "approved", "partial", "partial_received"]);

interface PoRow {
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string;
  supplierName: string | null;
  poStatus: string;
  updatedAt: string | null;
}

interface PoLine {
  lineId: string;
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice: string;
}

interface PoDetail {
  id: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierName: string;
  status: string;
  lines: PoLine[];
}

interface Product {
  productId: string;
  sku: string;
  name: string;
  standardCost: string;
  status: string;
}

interface DraftLine {
  purchaseOrderLineId: string;
  productId: string;
  productSku: string;
  productName: string;
  receivedQuantity: string;
  unitCost: string;
}

interface CreatedReceipt {
  id: string;
}

/** Post-goods-receipt form. Warehouse clerk authoring path. */
export function GoodsReceiptNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: purchaseOrders } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: () => apiGet<PoRow[]>("/api/purchase-orders"),
    refetchInterval: 5_000,
  });
  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const purchasable = (products ?? []).filter((p) => p.status === "active");

  const sortedPOs = (purchaseOrders ?? []).slice().sort((a, b) =>
    (b.updatedAt ?? "").localeCompare(a.updatedAt ?? "")
  );
  const openPOs = sortedPOs.filter((po) => RECEIVABLE_STATUSES.has(po.poStatus));

  const [receiptNumber, setReceiptNumber] = useState(() => `GR-${Date.now()}`);
  const [purchaseOrderHeaderId, setPurchaseOrderHeaderId] = useState("");
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [lines, setLines] = useState<DraftLine[]>([]);

  // Auto-select most recent open PO once data lands.
  useEffect(() => {
    if (!purchaseOrderHeaderId && openPOs[0]) {
      setPurchaseOrderHeaderId(openPOs[0].purchaseOrderHeaderId);
    }
  }, [openPOs, purchaseOrderHeaderId]);

  // Fetch picked PO's full detail (header + lines) so the receipt lines can
  // prefill with the correct (poLineId, productId) pair — the §1B.9 backend
  // validation rejects receipt lines whose pair doesn't match the PO line.
  const { data: poDetail } = useQuery({
    queryKey: ["purchase-order-cmd-detail", purchaseOrderHeaderId],
    queryFn: () => apiGet<PoDetail>(`/api/purchase-orders-cmd/${purchaseOrderHeaderId}`),
    enabled: !!purchaseOrderHeaderId,
  });

  // Reset draft lines whenever the picked PO changes.
  useEffect(() => {
    if (!poDetail) return;
    if (poDetail.lines.length === 0) return;
    setLines(poDetail.lines.map((l) => {
      const p = purchasable.find((p) => p.productId === l.productId);
      return {
        purchaseOrderLineId: l.lineId,
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        receivedQuantity: l.orderedQuantity,
        unitCost: l.unitPrice ?? p?.standardCost ?? "0",
      };
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [poDetail]);

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    const usedLineIds = new Set(lines.map((l) => l.purchaseOrderLineId));
    const next = poDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      const p = purchasable.find((p) => p.productId === next.productId);
      setLines((prev) => [...prev, {
        purchaseOrderLineId: next.lineId,
        productId: next.productId,
        productSku: next.productSku,
        productName: next.productName,
        receivedQuantity: next.orderedQuantity,
        unitCost: next.unitPrice ?? p?.standardCost ?? "0",
      }]);
    }
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedReceipt>("/api/goods-receipts", {
      goodsReceiptNumber: receiptNumber.trim(),
      purchaseOrderHeaderId,
      supplierId: poDetail?.supplierId ?? null,
      supplierName: poDetail?.supplierName ?? null,
      warehouseCode,
      lines: lines.map((l) => ({
        purchaseOrderLineId: l.purchaseOrderLineId || null,
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        receivedQuantity: l.receivedQuantity,
        unitCost: l.unitCost,
      })),
    }),
    onSuccess: () => {
      toast.success(`Goods receipt ${receiptNumber.trim()} posted.`);
      queryClient.invalidateQueries({ queryKey: ["goods-receipts"] });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      navigate("/goods-receipts");
    },
    onError: (e) =>
      toast.error(`Post failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    receiptNumber.trim().length > 0
    && purchaseOrderHeaderId.length > 0
    && warehouseCode.trim().length > 0
    && lines.length > 0
    && lines.every((l) => l.productId && Number(l.receivedQuantity) > 0)
    && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="Post goods receipt"
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Goods Receipts", to: "/goods-receipts" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/goods-receipts")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="warehouse_clerk"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Post receipt
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <p className="mb-4 max-w-3xl text-sm text-text-muted">
          Post a receipt against an open purchase order. Inventory bumps{" "}
          <code>stock_balance</code> and emits <code>inventory.GoodsReceived</code> — the make-to-order
          saga un-parks if it was on <code>raw_material_shortage</code>, and the P2P saga advances
          to <code>goods_received</code>.
        </p>

        <div className="grid gap-4 max-w-5xl">
          <div className="grid gap-4 lg:grid-cols-3">
            <FormSection title="Header">
              <Field label="Receipt number" required>
                <input
                  type="text"
                  value={receiptNumber}
                  onChange={(e) => setReceiptNumber(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                />
              </Field>
              <Field label="Warehouse code" required>
                <input
                  type="text"
                  value={warehouseCode}
                  onChange={(e) => setWarehouseCode(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                />
              </Field>
            </FormSection>

            <FormSection title="Purchase order">
              <Field label="Order" required hint={
                openPOs.length === 0
                  ? "no open POs — drive a shortage to auto-create one, or place a manual PR"
                  : `${openPOs.length} open PO${openPOs.length === 1 ? "" : "s"}`
              }>
                <select
                  value={purchaseOrderHeaderId}
                  onChange={(e) => setPurchaseOrderHeaderId(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                >
                  <option value="">— pick a purchase order —</option>
                  {openPOs.length > 0 && (
                    <optgroup label="Open">
                      {openPOs.map((po) => (
                        <option key={po.purchaseOrderHeaderId} value={po.purchaseOrderHeaderId}>
                          {po.purchaseOrderNumber} · {po.supplierName ?? "—"} · {po.poStatus}
                        </option>
                      ))}
                    </optgroup>
                  )}
                  {sortedPOs.filter((po) => !RECEIVABLE_STATUSES.has(po.poStatus)).length > 0 && (
                    <optgroup label="Other (advanced)">
                      {sortedPOs
                        .filter((po) => !RECEIVABLE_STATUSES.has(po.poStatus))
                        .map((po) => (
                          <option key={po.purchaseOrderHeaderId} value={po.purchaseOrderHeaderId}>
                            {po.purchaseOrderNumber} · {po.supplierName ?? "—"} · {po.poStatus}
                          </option>
                        ))}
                    </optgroup>
                  )}
                </select>
              </Field>
            </FormSection>
          </div>

          <FormSection title="Lines">
            <div className="flex items-center justify-between">
              <p className="text-xs text-text-muted">
                Lines auto-fill from the picked PO. Pick a different product per line only if a substitution
                is recorded (server rejects with 400 if {`(poLineId, productId)`} doesn't match the PO line).
              </p>
              <ActionButton icon={<Plus className="h-4 w-4" />} onClick={addLine} disabled={!poDetail}>
                Add line
              </ActionButton>
            </div>
            <table className="mt-2 w-full text-sm">
              <thead className="border-b border-border-default text-left text-[11px] uppercase tracking-wider text-text-muted">
                <tr>
                  <th className="py-2 pr-3 font-semibold">Product</th>
                  <th className="py-2 pr-3 font-semibold">PO line</th>
                  <th className="py-2 pr-3 text-right font-semibold">Received qty</th>
                  <th className="py-2 pr-3 text-right font-semibold">Unit cost</th>
                  <th className="py-2 font-semibold"></th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, idx) => (
                  <tr key={idx} className="border-b border-border-default last:border-b-0">
                    <td className="py-2 pr-3">
                      <select
                        value={line.productId}
                        onChange={(e) => {
                          const p = purchasable.find((p) => p.productId === e.target.value);
                          if (p) {
                            updateLine(idx, {
                              productId: p.productId,
                              productSku: p.sku,
                              productName: p.name,
                              unitCost: p.standardCost,
                            });
                          }
                        }}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-sm focus:border-border-focus focus:outline-none"
                      >
                        {purchasable.map((p) => (
                          <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                        ))}
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      <span className="font-mono text-[11px] text-text-faint">
                        {line.purchaseOrderLineId
                          ? `${line.purchaseOrderLineId.slice(0, 8)}…`
                          : "(unmatched)"}
                      </span>
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number" step="0.01" min="0.01"
                        value={line.receivedQuantity}
                        onChange={(e) => updateLine(idx, { receivedQuantity: e.target.value })}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number" step="0.01" min="0"
                        value={line.unitCost}
                        onChange={(e) => updateLine(idx, { unitCost: e.target.value })}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
                      />
                    </td>
                    <td className="py-2 text-right">
                      <ActionButton variant="ghost" onClick={() => removeLine(idx)}>
                        <Trash2 className="h-3.5 w-3.5" />
                      </ActionButton>
                    </td>
                  </tr>
                ))}
                {lines.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-6 text-center text-sm text-text-faint">
                      Pick a purchase order above; lines auto-fill from its open lines.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </FormSection>
        </div>
      </div>
    </>
  );
}
