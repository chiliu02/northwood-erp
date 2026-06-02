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

interface SalesOrderRow {
  salesOrderHeaderId: string;
  orderNumber: string;
  customerName: string | null;
  orderStatus: string;
  shipmentStatus: string | null;
  updatedAt: string | null;
}

interface SalesOrderLine {
  lineId: string;
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice: string;
}

interface SalesOrderDetail {
  id: string;
  orderNumber: string;
  customerName: string;
  status: string;
  currencyCode: string;
  lines: SalesOrderLine[];
}

interface Product {
  productId: string;
  sku: string;
  name: string;
  standardCost: string;
  status: string;
}

interface DraftLine {
  salesOrderLineId: string;
  productId: string;
  productSku: string;
  productName: string;
  shippedQuantity: string;
  unitCost: string;
}

interface CreatedShipment {
  id: string;
}

/** Post-shipment form. Warehouse clerk authoring path. */
export function ShipmentNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: salesOrders } = useQuery({
    queryKey: ["sales-orders"],
    queryFn: () => apiGet<SalesOrderRow[]>("/api/sales-orders"),
    refetchInterval: 5_000,
  });
  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const sellable = (products ?? []).filter((p) => p.status === "active");

  // Sales orders that are ready to ship; show others greyed out as fallback
  // so a power user can ship against any SO if needed (the backend enforces
  // the actual state machine).
  const shippableOrders = (salesOrders ?? []).slice().sort((a, b) =>
    (b.updatedAt ?? "").localeCompare(a.updatedAt ?? "")
  );
  const readyToShip = shippableOrders.filter((o) => o.orderStatus === "ready_to_ship");

  const [shipmentNumber, setShipmentNumber] = useState(() => `SH-${Date.now()}`);
  const [salesOrderHeaderId, setSalesOrderHeaderId] = useState("");
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [lines, setLines] = useState<DraftLine[]>([]);

  // Auto-select the most recent ready-to-ship order once data lands.
  useEffect(() => {
    if (!salesOrderHeaderId && readyToShip[0]) {
      setSalesOrderHeaderId(readyToShip[0].salesOrderHeaderId);
    }
  }, [readyToShip, salesOrderHeaderId]);

  // Fetch the picked SO's lines so the shipment lines can prefill with the
  // correct line UUIDs — the backend validation rejects shipment
  // lines whose (salesOrderLineId, productId) pair doesn't match the SO.
  const { data: orderDetail } = useQuery({
    queryKey: ["sales-order-cmd-detail", salesOrderHeaderId],
    queryFn: () => apiGet<SalesOrderDetail>(`/api/sales-cmd/sales-orders/${salesOrderHeaderId}`),
    enabled: !!salesOrderHeaderId,
  });

  // Reset draft lines whenever the picked order changes.
  useEffect(() => {
    if (!orderDetail) return;
    if (orderDetail.lines.length === 0) return;
    setLines(orderDetail.lines.map((l) => {
      const p = sellable.find((p) => p.productId === l.productId);
      return {
        salesOrderLineId: l.lineId,
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        shippedQuantity: l.orderedQuantity,
        unitCost: p?.standardCost ?? "0",
      };
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderDetail]);

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    // Prefer to add an SO line that isn't already in the draft.
    const usedLineIds = new Set(lines.map((l) => l.salesOrderLineId));
    const next = orderDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      const p = sellable.find((p) => p.productId === next.productId);
      setLines((prev) => [...prev, {
        salesOrderLineId: next.lineId,
        productId: next.productId,
        productSku: next.productSku,
        productName: next.productName,
        shippedQuantity: next.orderedQuantity,
        unitCost: p?.standardCost ?? "0",
      }]);
    }
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedShipment>("/api/shipments", {
      shipmentNumber: shipmentNumber.trim(),
      salesOrderHeaderId,
      warehouseCode,
      lines: lines.map((l) => ({
        salesOrderLineId: l.salesOrderLineId || null,
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        shippedQuantity: l.shippedQuantity,
        unitCost: l.unitCost,
      })),
    }),
    onSuccess: () => {
      toast.success(`Shipment ${shipmentNumber.trim()} posted.`);
      queryClient.invalidateQueries({ queryKey: ["shipments"] });
      queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
      navigate("/shipments");
    },
    onError: (e) =>
      toast.error(`Post failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    shipmentNumber.trim().length > 0
    && salesOrderHeaderId.length > 0
    && warehouseCode.trim().length > 0
    && lines.length > 0
    && lines.every((l) => l.productId && Number(l.shippedQuantity) > 0)
    && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="Post shipment"
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Shipments", to: "/shipments" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/shipments")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="warehouse_clerk"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Post shipment
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <p className="mb-4 max-w-3xl text-sm text-text-muted">
          Ship goods against a fulfilment-ready sales order. The fulfilment saga advances{" "}
          <code>ready_to_ship → goods_shipped</code>; finance auto-creates the customer invoice;
          inventory decrements on-hand and releases the reservation.
        </p>

        <div className="grid gap-4 max-w-5xl">
          <div className="grid gap-4 lg:grid-cols-3">
            <FormSection title="Header">
              <Field label="Shipment number" required>
                <input
                  type="text"
                  value={shipmentNumber}
                  onChange={(e) => setShipmentNumber(e.target.value)}
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

            <FormSection title="Sales order">
              <Field label="Order" required hint={
                readyToShip.length === 0
                  ? "no orders are ready_to_ship yet — drive an order through the saga first"
                  : `${readyToShip.length} ready_to_ship order${readyToShip.length === 1 ? "" : "s"}`
              }>
                <select
                  value={salesOrderHeaderId}
                  onChange={(e) => setSalesOrderHeaderId(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                >
                  <option value="">— pick a sales order —</option>
                  {readyToShip.length > 0 && (
                    <optgroup label="Ready to ship">
                      {readyToShip.map((o) => (
                        <option key={o.salesOrderHeaderId} value={o.salesOrderHeaderId}>
                          {o.orderNumber} · {o.customerName ?? "—"}
                        </option>
                      ))}
                    </optgroup>
                  )}
                  {shippableOrders.filter((o) => o.orderStatus !== "ready_to_ship").length > 0 && (
                    <optgroup label="Other (advanced)">
                      {shippableOrders
                        .filter((o) => o.orderStatus !== "ready_to_ship")
                        .map((o) => (
                          <option key={o.salesOrderHeaderId} value={o.salesOrderHeaderId}>
                            {o.orderNumber} · {o.customerName ?? "—"} · {o.orderStatus}
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
                Lines auto-fill from the picked SO. Pick a different product per line if a substitution is
                needed (server rejects with 400 if {`(soLineId, productId)`} doesn't match the SO line).
              </p>
              <ActionButton icon={<Plus className="h-4 w-4" />} onClick={addLine} disabled={!orderDetail}>
                Add line
              </ActionButton>
            </div>
            <table className="mt-2 w-full text-sm">
              <thead className="border-b border-border-default text-left text-[11px] uppercase tracking-wider text-text-muted">
                <tr>
                  <th className="py-2 pr-3 font-semibold">Product</th>
                  <th className="py-2 pr-3 font-semibold">SO line</th>
                  <th className="py-2 pr-3 text-right font-semibold">Shipped qty</th>
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
                          const p = sellable.find((p) => p.productId === e.target.value);
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
                        {sellable.map((p) => (
                          <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                        ))}
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      <span className="font-mono text-[11px] text-text-faint">
                        {line.salesOrderLineId
                          ? `${line.salesOrderLineId.slice(0, 8)}…`
                          : "(unmatched)"}
                      </span>
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number" step="0.01" min="0.01"
                        value={line.shippedQuantity}
                        onChange={(e) => updateLine(idx, { shippedQuantity: e.target.value })}
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
                      Pick a sales order above; lines auto-fill from its open lines.
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
