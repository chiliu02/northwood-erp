import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { fetchProducts, fetchPurchaseOrders, fetchPurchaseOrderDetail } from "@/api/fetchers";
import { postGoodsReceipt } from "@/api/commands";
import type { PostGoodsReceiptLine } from "@/api/types-commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";

interface DraftLine {
  purchaseOrderLineId: string;
  productId: string;
  receivedQuantity: string;
  unitCost: string;
}

const DEFAULT_WAREHOUSE = "MAIN";

// PO statuses that are receivable. The backend ultimately enforces; this just
// scopes the picker.
const RECEIVABLE_STATUSES = new Set(["sent", "approved", "partial", "partial_received"]);

export function GoodsReceipts() {
  const persona = PERSONAS.mike;
  const { data: products } = useQuery({ queryKey: ["products"], queryFn: fetchProducts });
  const { data: purchaseOrders } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: fetchPurchaseOrders,
    refetchInterval: 5_000,
  });

  const purchasable = (products ?? []).filter((p) => p.status === "active");
  const firstProduct = purchasable[0];

  const sortedPOs = (purchaseOrders ?? []).slice().sort((a, b) =>
    (b.updatedAt ?? "").localeCompare(a.updatedAt ?? "")
  );
  const openPOs = sortedPOs.filter((po) => RECEIVABLE_STATUSES.has(po.poStatus));

  const [receiptNumber, setReceiptNumber] = useState(() => `GR-${Date.now()}`);
  const [purchaseOrderHeaderId, setPurchaseOrderHeaderId] = useState("");
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Auto-select most recent open PO once data lands.
  useEffect(() => {
    if (!purchaseOrderHeaderId && openPOs[0]) {
      setPurchaseOrderHeaderId(openPOs[0].purchaseOrderHeaderId);
    }
  }, [openPOs, purchaseOrderHeaderId]);

  // Fetch picked PO's full detail (header + lines) so the receipt lines can
  // prefill from the actual order — same problem as the Shipments form, where
  // free-typing a UUID and defaulting lines independently of the picked
  // order led to product mismatch + DB CHECK violations on stock_balance.
  const { data: poDetail } = useQuery({
    queryKey: ["purchase-order-detail", purchaseOrderHeaderId],
    queryFn: () => fetchPurchaseOrderDetail(purchaseOrderHeaderId),
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
        receivedQuantity: l.orderedQuantity,
        unitCost: l.unitPrice ?? p?.standardCost ?? "0",
      };
    }));
  }, [poDetail]);  // eslint-disable-line react-hooks/exhaustive-deps

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    // Prefer a PO line that isn't already drafted; falls back to firstProduct.
    const usedLineIds = new Set(lines.map((l) => l.purchaseOrderLineId));
    const next = poDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      const p = purchasable.find((p) => p.productId === next.productId);
      setLines((prev) => [...prev, {
        purchaseOrderLineId: next.lineId,
        productId: next.productId,
        receivedQuantity: next.orderedQuantity,
        unitCost: next.unitPrice ?? p?.standardCost ?? "0",
      }]);
      return;
    }
    if (!firstProduct) return;
    setLines((prev) => [...prev, {
      purchaseOrderLineId: "",
      productId: firstProduct.productId,
      receivedQuantity: "1",
      unitCost: firstProduct.standardCost,
    }]);
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const apiLines: PostGoodsReceiptLine[] = lines.map((l) => {
        const p = purchasable.find((p) => p.productId === l.productId);
        if (!p) throw new Error(`Unknown product ${l.productId}`);
        return {
          purchaseOrderLineId: l.purchaseOrderLineId || undefined,
          productId: p.productId,
          productSku: p.sku,
          productName: p.name,
          receivedQuantity: l.receivedQuantity,
          unitCost: l.unitCost,
        };
      });
      const result = await postGoodsReceipt({
        goodsReceiptNumber: receiptNumber,
        purchaseOrderHeaderId,
        warehouseCode,
        lines: apiLines,
      });
      setSubmit({ status: "success", message: `posted ${result.id ?? receiptNumber}` });
      setReceiptNumber(`GR-${Date.now()}`);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Goods receipt" persona={<PersonaTag {...persona} />}>
      <p className="mb-4 text-sm text-text-muted">
        Post a receipt against an open purchase order. Inventory bumps stock_balance and emits
        <span className="font-mono"> inventory.GoodsReceived</span> — you'll see the manufacturing make-to-order saga
        un-park (if it was on <span className="font-mono">raw_material_shortage</span>) and the P2P saga advance.
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <FieldRow label="Receipt number" required>
          <Input value={receiptNumber} onChange={(e) => setReceiptNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Purchase order" required hint={
          openPOs.length === 0
            ? "no open POs — drive a shortage to auto-create one, or place a manual PR"
            : `${openPOs.length} open PO${openPOs.length === 1 ? "" : "s"}`
        }>
          <Select value={purchaseOrderHeaderId} onChange={(e) => setPurchaseOrderHeaderId(e.target.value)}>
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
          </Select>
        </FieldRow>
        <FieldRow label="Warehouse code" required>
          <Input value={warehouseCode} onChange={(e) => setWarehouseCode(e.target.value)} />
        </FieldRow>
      </div>

      <div className="mt-6">
        <div className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-text-muted">Lines</h2>
          <Button variant="secondary" onClick={addLine}>
            <Plus className="-ml-0.5 mr-1 inline h-3.5 w-3.5" /> add line
          </Button>
        </div>
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="py-2 pr-3 font-semibold">Product</th>
              <th className="py-2 pr-3 font-semibold">PO line id (optional)</th>
              <th className="py-2 pr-3 text-right font-semibold">Received qty</th>
              <th className="py-2 pr-3 text-right font-semibold">Unit cost</th>
              <th className="py-2 font-semibold"></th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, idx) => (
              <tr key={idx} className="border-b border-border-subtle last:border-b-0">
                <td className="py-2 pr-3">
                  <Select
                    value={line.productId}
                    onChange={(e) => {
                      const p = purchasable.find((p) => p.productId === e.target.value);
                      updateLine(idx, {
                        productId: e.target.value,
                        unitCost: p?.standardCost ?? line.unitCost,
                      });
                    }}
                  >
                    {purchasable.map((p) => (
                      <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                    ))}
                  </Select>
                </td>
                <td className="py-2 pr-3">
                  <Input
                    placeholder="optional"
                    value={line.purchaseOrderLineId}
                    onChange={(e) => updateLine(idx, { purchaseOrderLineId: e.target.value })}
                  />
                </td>
                <td className="py-2 pr-3">
                  <Input
                    type="number"
                    step="0.01"
                    min="0.01"
                    className="text-right"
                    value={line.receivedQuantity}
                    onChange={(e) => updateLine(idx, { receivedQuantity: e.target.value })}
                  />
                </td>
                <td className="py-2 pr-3">
                  <Input
                    type="number"
                    step="0.01"
                    min="0"
                    className="text-right"
                    value={line.unitCost}
                    onChange={(e) => updateLine(idx, { unitCost: e.target.value })}
                  />
                </td>
                <td className="py-2 text-right">
                  <Button variant="ghost" onClick={() => removeLine(idx)} disabled={lines.length === 1}>
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-6 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="primary"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || lines.length === 0 || !purchaseOrderHeaderId}
        >
          Post receipt
        </Button>
      </div>
    </FormCard>
  );
}
