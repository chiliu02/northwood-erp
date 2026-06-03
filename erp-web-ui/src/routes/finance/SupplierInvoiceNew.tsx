import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";

// PO statuses where it makes sense to record an invoice (received goods on a
// non-cancelled, non-closed PO).
const INVOICEABLE_STATUSES = new Set(["sent", "approved", "partial", "partial_received", "received"]);

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
  currencyCode: string;
  lines: PoLine[];
}

interface GoodsReceiptRow {
  id: string;
  goodsReceiptNumber: string;
  purchaseOrderHeaderId: string;
  status: string;
}

interface DraftLine {
  purchaseOrderLineId: string;
  productId: string;
  productSku: string;
  productName: string;
  quantity: string;
  unitPrice: string;
}

interface CreatedInvoice {
  id: string;
}

/** Record-supplier-invoice form. Accountant authoring path. */
export function SupplierInvoiceNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: purchaseOrders } = useQuery({
    queryKey: ["purchase-orders"],
    queryFn: () => apiGet<PoRow[]>("/api/purchase-orders"),
    refetchInterval: 5_000,
  });
  const { data: goodsReceipts } = useQuery({
    queryKey: ["goods-receipts"],
    queryFn: () => apiGet<GoodsReceiptRow[]>("/api/goods-receipts"),
    refetchInterval: 5_000,
  });

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

  // Auto-select most recent open PO once data lands.
  useEffect(() => {
    if (!purchaseOrderHeaderId && openPOs[0]) {
      setPurchaseOrderHeaderId(openPOs[0].purchaseOrderHeaderId);
    }
  }, [openPOs, purchaseOrderHeaderId]);

  // Fetch the picked PO's full detail (header + lines). Auto-fills supplier
  // identity, currency, and lines from the PO so the user can't typo a UUID
  // or mismatch supplier-vs-PO.
  const { data: poDetail } = useQuery({
    queryKey: ["purchase-order-cmd-detail", purchaseOrderHeaderId],
    queryFn: () => apiGet<PoDetail>(`/api/purchase-orders-cmd/${purchaseOrderHeaderId}`),
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
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        quantity: l.orderedQuantity,
        unitPrice: l.unitPrice,
      })));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [poDetail]);

  // Goods receipts for the picked PO (most-recent-first by virtue of how the
  // inventory list returns them).
  const receiptsForPo = (goodsReceipts ?? []).filter(
    (r) => r.purchaseOrderHeaderId === purchaseOrderHeaderId
  );

  // Clear a stale goods-receipt pick when the PO changes.
  useEffect(() => {
    if (goodsReceiptHeaderId && !receiptsForPo.some((r) => r.id === goodsReceiptHeaderId)) {
      setGoodsReceiptHeaderId("");
    }
  }, [purchaseOrderHeaderId, receiptsForPo, goodsReceiptHeaderId]);

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    const usedLineIds = new Set(lines.map((l) => l.purchaseOrderLineId));
    const next = poDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      setLines((prev) => [...prev, {
        purchaseOrderLineId: next.lineId,
        productId: next.productId,
        productSku: next.productSku,
        productName: next.productName,
        quantity: next.orderedQuantity,
        unitPrice: next.unitPrice,
      }]);
    }
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedInvoice>("/api/supplier-invoices", {
      internalInvoiceNumber: internalInvoiceNumber.trim(),
      supplierInvoiceNumber: supplierInvoiceNumber.trim(),
      purchaseOrderHeaderId,
      goodsReceiptHeaderId: goodsReceiptHeaderId || null,
      supplierId,
      supplierName,
      currencyCode: currencyCode.trim().toUpperCase(),
      lines: lines.map((l) => ({
        purchaseOrderLineId: l.purchaseOrderLineId,
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        quantity: l.quantity,
        unitPrice: l.unitPrice,
      })),
    }),
    onSuccess: (created) => {
      toast.success(`Supplier invoice ${internalInvoiceNumber.trim()} recorded.`);
      queryClient.invalidateQueries({ queryKey: ["supplier-invoices"] });
      queryClient.invalidateQueries({ queryKey: ["pending-review"] });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      navigate(`/supplier-invoices/${created.id}`);
    },
    onError: (e) =>
      toast.error(`Record failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    internalInvoiceNumber.trim().length > 0
    && supplierInvoiceNumber.trim().length > 0
    && purchaseOrderHeaderId.length > 0
    && supplierId.length > 0
    && currencyCode.trim().length === 3
    && lines.length > 0
    && lines.every((l) => l.productId && l.purchaseOrderLineId && Number(l.quantity) > 0)
    && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="Record supplier invoice"
        trail={[
          { label: "Finance" },
          { label: "Supplier Invoices", to: "/supplier-invoices" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/supplier-invoices")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="accountant"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Record invoice
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <p className="mb-4 max-w-3xl text-sm text-text-muted">
          Record a supplier invoice and run the (quantity + price) 3-way match. On match, finance emits{" "}
          <code>finance.SupplierInvoiceApproved</code> and the P2P saga advances. Variance-failed invoices
          park for manual review at <code>/supplier-invoices/pending-review</code>.
        </p>

        <div className="grid gap-4 max-w-5xl">
          <div className="grid gap-4 lg:grid-cols-3">
            <FormSection title="Identity">
              <Field label="Internal invoice number" required>
                <input
                  type="text"
                  value={internalInvoiceNumber}
                  onChange={(e) => setInternalInvoiceNumber(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                />
              </Field>
              <Field label="Supplier invoice number" required hint="from the supplier's paperwork">
                <input
                  type="text"
                  value={supplierInvoiceNumber}
                  onChange={(e) => setSupplierInvoiceNumber(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                />
              </Field>
              <Field label="Currency" required>
                <input
                  type="text"
                  value={currencyCode}
                  onChange={(e) => setCurrencyCode(e.target.value)}
                  maxLength={3}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm uppercase focus:border-border-focus focus:outline-none"
                />
              </Field>
            </FormSection>

            <FormSection title="Purchase order">
              <Field label="PO" required hint={
                openPOs.length === 0
                  ? "no open POs — drive a shortage to auto-create one, or approve a manual PR"
                  : `${openPOs.length} open PO${openPOs.length === 1 ? "" : "s"}`
              }>
                <select
                  value={purchaseOrderHeaderId}
                  onChange={(e) => setPurchaseOrderHeaderId(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                >
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
                </select>
              </Field>
              <Field label="Goods receipt" hint={
                purchaseOrderHeaderId
                  ? receiptsForPo.length === 0
                    ? "optional — no receipts posted against this PO yet"
                    : `optional — ${receiptsForPo.length} receipt${receiptsForPo.length === 1 ? "" : "s"} for this PO`
                  : "optional — pick a PO first"
              }>
                <select
                  value={goodsReceiptHeaderId}
                  onChange={(e) => setGoodsReceiptHeaderId(e.target.value)}
                  disabled={!purchaseOrderHeaderId || receiptsForPo.length === 0}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <option value="">— none —</option>
                  {receiptsForPo.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.goodsReceiptNumber} · {r.status}
                    </option>
                  ))}
                </select>
              </Field>
            </FormSection>

            <FormSection title="Supplier">
              <Field label="Supplier" hint="auto-filled from PO">
                <input
                  type="text"
                  value={`${supplierName}${supplierId ? ` (${supplierId.slice(0, 8)}…)` : ""}`}
                  disabled
                  className="h-9 w-full rounded-md border border-border-default bg-bg-subtle px-3 text-sm text-text-muted"
                />
              </Field>
            </FormSection>
          </div>

          <FormSection title="Lines">
            <div className="flex items-center justify-between">
              <p className="text-xs text-text-muted">
                Lines auto-fill from the picked PO. Per-line quantity + unit price are editable;
                3-way match runs server-side. Variance &gt; tolerance parks at <code>three_way_match_failed</code>.
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
                  <th className="py-2 pr-3 text-right font-semibold">Quantity</th>
                  <th className="py-2 pr-3 text-right font-semibold">Unit price</th>
                  <th className="py-2 font-semibold"></th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, idx) => (
                  <tr key={idx} className="border-b border-border-default last:border-b-0">
                    <td className="py-2 pr-3">
                      <span className="font-medium">{line.productSku}</span>
                      <span className="ml-1 text-text-muted">· {line.productName}</span>
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
                        value={line.quantity}
                        onChange={(e) => updateLine(idx, { quantity: e.target.value })}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-right text-sm focus:border-border-focus focus:outline-none"
                      />
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number" step="0.01" min="0"
                        value={line.unitPrice}
                        onChange={(e) => updateLine(idx, { unitPrice: e.target.value })}
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
