import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { fetchGoodsReceipts, fetchProducts, fetchPurchaseOrders, fetchPurchaseOrderDetail } from "@/api/fetchers";
import { recordSupplierInvoice } from "@/api/commands";
import type { RecordSupplierInvoiceLine } from "@/api/types-commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";

interface DraftLine {
  purchaseOrderLineId: string;
  goodsReceiptLineId: string;
  productId: string;
  quantity: string;
  unitPrice: string;
}

// PO statuses where it makes sense to record an invoice (received goods on a
// non-cancelled, non-closed PO).
const INVOICEABLE_STATUSES = new Set(["sent", "approved", "partial", "partial_received", "received"]);

export function SupplierInvoices() {
  const persona = PERSONAS.olivia;
  const { data: products } = useQuery({ queryKey: ["products"], queryFn: fetchProducts });
  const { data: purchaseOrders } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: fetchPurchaseOrders,
    refetchInterval: 5_000,
  });
  const { data: goodsReceipts } = useQuery({
    queryKey: ["goods-receipts"],
    queryFn: fetchGoodsReceipts,
    refetchInterval: 5_000,
  });

  const purchasable = (products ?? []).filter((p) => p.status === "active");

  const sortedPOs = (purchaseOrders ?? []).slice().sort((a, b) =>
    (b.updatedAt ?? "").localeCompare(a.updatedAt ?? "")
  );
  const openPOs = sortedPOs.filter((po) => INVOICEABLE_STATUSES.has(po.poStatus));

  const [internalInvoiceNumber, setInternalInvoiceNumber] = useState(() => `INV-${Date.now()}`);
  const [supplierInvoiceNumber, setSupplierInvoiceNumber] = useState("");
  const [purchaseOrderHeaderId, setPurchaseOrderHeaderId] = useState("");
  const [goodsReceiptHeaderId, setGoodsReceiptHeaderId] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [supplierName, setSupplierName] = useState("");
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Auto-select most recent open PO once data lands.
  useEffect(() => {
    if (!purchaseOrderHeaderId && openPOs[0]) {
      setPurchaseOrderHeaderId(openPOs[0].purchaseOrderHeaderId);
    }
  }, [openPOs, purchaseOrderHeaderId]);

  // Goods receipts for the picked PO (already returned sorted most-recent-first
  // by inventory's findAllHeaders).
  const receiptsForPo = (goodsReceipts ?? []).filter(
    (r) => r.purchaseOrderHeaderId === purchaseOrderHeaderId
  );

  // Clear a stale goods-receipt pick when the PO changes — receipts for the
  // previous PO are no longer valid options.
  useEffect(() => {
    if (goodsReceiptHeaderId && !receiptsForPo.some((r) => r.id === goodsReceiptHeaderId)) {
      setGoodsReceiptHeaderId("");
    }
  }, [purchaseOrderHeaderId, receiptsForPo, goodsReceiptHeaderId]);

  // Fetch the picked PO's full detail (header + lines). Auto-fills supplier
  // identity, currency, and lines from the PO so the user can't typo a UUID
  // or mismatch supplier-vs-PO. Same fix pattern as Shipments + GoodsReceipts.
  const { data: poDetail } = useQuery({
    queryKey: ["purchase-order-detail", purchaseOrderHeaderId],
    queryFn: () => fetchPurchaseOrderDetail(purchaseOrderHeaderId),
    enabled: !!purchaseOrderHeaderId,
  });

  useEffect(() => {
    if (!poDetail) return;
    setSupplierId(poDetail.supplierId);
    setSupplierName(poDetail.supplierName);
    setCurrencyCode(poDetail.currencyCode);
    if (poDetail.lines.length > 0) {
      setLines(poDetail.lines.map((l) => ({
        purchaseOrderLineId: l.lineId,
        goodsReceiptLineId: "",
        productId: l.productId,
        quantity: l.orderedQuantity,
        unitPrice: l.unitPrice,
      })));
    }
  }, [poDetail]);  // eslint-disable-line react-hooks/exhaustive-deps

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    const usedLineIds = new Set(lines.map((l) => l.purchaseOrderLineId));
    const next = poDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      setLines((prev) => [...prev, {
        purchaseOrderLineId: next.lineId,
        goodsReceiptLineId: "",
        productId: next.productId,
        quantity: next.orderedQuantity,
        unitPrice: next.unitPrice,
      }]);
      return;
    }
    if (purchasable.length === 0) return;
    const fallback = purchasable[0];
    setLines((prev) => [...prev, {
      purchaseOrderLineId: "",
      goodsReceiptLineId: "",
      productId: fallback.productId,
      quantity: "1",
      unitPrice: fallback.standardCost,
    }]);
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const apiLines: RecordSupplierInvoiceLine[] = lines.map((l) => {
        const p = purchasable.find((p) => p.productId === l.productId);
        if (!p) throw new Error(`Unknown product ${l.productId}`);
        return {
          purchaseOrderLineId: l.purchaseOrderLineId,
          goodsReceiptLineId: l.goodsReceiptLineId || undefined,
          productId: p.productId,
          productSku: p.sku,
          productName: p.name,
          quantity: l.quantity,
          unitPrice: l.unitPrice,
        };
      });
      const result = await recordSupplierInvoice({
        internalInvoiceNumber,
        supplierInvoiceNumber,
        purchaseOrderHeaderId,
        goodsReceiptHeaderId: goodsReceiptHeaderId || undefined,
        supplierId,
        supplierName,
        currencyCode,
        lines: apiLines,
      });
      setSubmit({ status: "success", message: `recorded ${result.id ?? internalInvoiceNumber}` });
      setInternalInvoiceNumber(`INV-${Date.now()}`);
      setSupplierInvoiceNumber("");
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Supplier invoice" persona={<PersonaTag {...persona} />}>
      <p className="mb-4 text-sm text-text-muted">
        Record a supplier invoice and run the (quantity + price) 3-way match. On match, finance emits
        <span className="font-mono"> finance.SupplierInvoiceApproved</span> and the P2P saga advances.
        Variance-failed invoices park for manual review on{" "}
        <span className="font-mono">/supplier-invoices/pending-review</span>.
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <FieldRow label="Internal invoice number" required>
          <Input value={internalInvoiceNumber} onChange={(e) => setInternalInvoiceNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Supplier invoice number" required hint="from the supplier's paperwork">
          <Input value={supplierInvoiceNumber} onChange={(e) => setSupplierInvoiceNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Currency" required>
          <Select value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value)}>
            <option value="AUD">AUD</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
          </Select>
        </FieldRow>
        <FieldRow label="Purchase order" required hint={
          openPOs.length === 0
            ? "no open POs — drive a shortage to auto-create one, or place a manual PR"
            : `${openPOs.length} open PO${openPOs.length === 1 ? "" : "s"}`
        }>
          <Select value={purchaseOrderHeaderId} onChange={(e) => setPurchaseOrderHeaderId(e.target.value)}>
            <option value="">— pick a PO —</option>
            {openPOs.length > 0 && (
              <optgroup label="Open">
                {openPOs.map((po) => (
                  <option key={po.purchaseOrderHeaderId} value={po.purchaseOrderHeaderId}>
                    {po.purchaseOrderNumber} · {po.supplierName ?? "—"} · {po.poStatus}
                  </option>
                ))}
              </optgroup>
            )}
            {sortedPOs.filter((po) => !INVOICEABLE_STATUSES.has(po.poStatus)).length > 0 && (
              <optgroup label="Other (advanced)">
                {sortedPOs
                  .filter((po) => !INVOICEABLE_STATUSES.has(po.poStatus))
                  .map((po) => (
                    <option key={po.purchaseOrderHeaderId} value={po.purchaseOrderHeaderId}>
                      {po.purchaseOrderNumber} · {po.supplierName ?? "—"} · {po.poStatus}
                    </option>
                  ))}
              </optgroup>
            )}
          </Select>
        </FieldRow>
        <FieldRow label="Goods receipt" hint={
          purchaseOrderHeaderId
            ? receiptsForPo.length === 0
              ? "optional — no receipts posted against this PO yet"
              : `optional — ${receiptsForPo.length} receipt${receiptsForPo.length === 1 ? "" : "s"} for this PO`
            : "optional — pick a PO first"
        }>
          <Select
            value={goodsReceiptHeaderId}
            onChange={(e) => setGoodsReceiptHeaderId(e.target.value)}
            disabled={!purchaseOrderHeaderId || receiptsForPo.length === 0}
          >
            <option value="">— none —</option>
            {receiptsForPo.map((r) => (
              <option key={r.id} value={r.id}>
                {r.goodsReceiptNumber} · {r.status}
              </option>
            ))}
          </Select>
        </FieldRow>
        <FieldRow label="Supplier" hint="auto-filled from PO">
          <Input value={`${supplierName}${supplierId ? ` (${supplierId.slice(0, 8)}…)` : ""}`} disabled />
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
              <th className="py-2 pr-3 font-semibold">PO line</th>
              <th className="py-2 pr-3 text-right font-semibold">Quantity</th>
              <th className="py-2 pr-3 text-right font-semibold">Unit price</th>
              <th className="py-2 font-semibold"></th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, idx) => (
              <tr key={idx} className="border-b border-border-subtle last:border-b-0">
                <td className="py-2 pr-3">
                  <Select
                    value={line.productId}
                    onChange={(e) => updateLine(idx, { productId: e.target.value })}
                  >
                    {purchasable.map((p) => (
                      <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                    ))}
                  </Select>
                </td>
                <td className="py-2 pr-3">
                  <span className="font-mono text-[11px] text-text-faint">
                    {line.purchaseOrderLineId
                      ? `${line.purchaseOrderLineId.slice(0, 8)}…`
                      : "(unmatched)"}
                  </span>
                </td>
                <td className="py-2 pr-3">
                  <Input
                    type="number" step="0.01" min="0.01"
                    className="text-right"
                    value={line.quantity}
                    onChange={(e) => updateLine(idx, { quantity: e.target.value })}
                  />
                </td>
                <td className="py-2 pr-3">
                  <Input
                    type="number" step="0.01" min="0"
                    className="text-right"
                    value={line.unitPrice}
                    onChange={(e) => updateLine(idx, { unitPrice: e.target.value })}
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
          disabled={submit.status === "submitting" || !purchaseOrderHeaderId || !supplierId || !supplierInvoiceNumber}
        >
          Record invoice
        </Button>
      </div>
    </FormCard>
  );
}
