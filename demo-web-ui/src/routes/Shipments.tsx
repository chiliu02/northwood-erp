import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { fetchProducts, fetchSalesOrderDetail, fetchSalesOrders } from "@/api/fetchers";
import { postShipment } from "@/api/commands";
import type { PostShipmentLine } from "@/api/types-commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";

interface DraftLine {
  salesOrderLineId: string;
  productId: string;
  shippedQuantity: string;
  unitCost: string;
}

const DEFAULT_WAREHOUSE = "MAIN";

export function Shipments() {
  const persona = PERSONAS.mike;
  const { data: products } = useQuery({ queryKey: ["products"], queryFn: fetchProducts });
  const { data: salesOrders } = useQuery({
    queryKey: ["sales-orders"],
    queryFn: fetchSalesOrders,
    refetchInterval: 5_000,
  });

  const sellable = (products ?? []).filter((p) => p.status === "active");
  const firstFinished = sellable.find((p) => p.productType === "finished_good") ?? sellable[0];

  // Sales orders that are ready to ship are the demo-relevant choices; show
  // others greyed out as fallback so a power user can still ship against any
  // SO if needed (the backend enforces the actual state machine).
  const shippableOrders = (salesOrders ?? []).slice().sort((a, b) =>
    (b.updatedAt ?? "").localeCompare(a.updatedAt ?? "")
  );
  const readyToShip = shippableOrders.filter((o) => o.orderStatus === "ready_to_ship");

  const [shipmentNumber, setShipmentNumber] = useState(() => `SH-${Date.now()}`);
  const [salesOrderHeaderId, setSalesOrderHeaderId] = useState("");
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Auto-select the most recent ready-to-ship order once data lands.
  useEffect(() => {
    if (!salesOrderHeaderId && readyToShip[0]) {
      setSalesOrderHeaderId(readyToShip[0].salesOrderHeaderId);
    }
  }, [readyToShip, salesOrderHeaderId]);

  // Fetch the picked SO's lines so the shipment lines can prefill from
  // the actual order (correct product + qty + the line UUID for the
  // backend to match against). Without this the lines defaulted to the
  // first finished good in the catalog, which mismatched the SO and
  // tripped the stock_balance check constraint when posting.
  const { data: orderDetail } = useQuery({
    queryKey: ["sales-order-detail", salesOrderHeaderId],
    queryFn: () => fetchSalesOrderDetail(salesOrderHeaderId),
    enabled: !!salesOrderHeaderId,
  });

  // Reset draft lines whenever the picked order changes (or first arrives).
  useEffect(() => {
    if (!orderDetail) return;
    if (orderDetail.lines.length === 0) return;
    setLines(orderDetail.lines.map((l) => {
      const p = sellable.find((p) => p.productId === l.productId);
      return {
        salesOrderLineId: l.lineId,
        productId: l.productId,
        shippedQuantity: l.orderedQuantity,
        unitCost: p?.standardCost ?? "0",
      };
    }));
  }, [orderDetail]);  // eslint-disable-line react-hooks/exhaustive-deps

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function addLine() {
    // Prefer to add an SO line that isn't already in the draft (so picking
    // multi-line orders fills naturally). Falls back to firstFinished if the
    // SO has no untouched lines or no SO is selected.
    const usedLineIds = new Set(lines.map((l) => l.salesOrderLineId));
    const next = orderDetail?.lines.find((l) => !usedLineIds.has(l.lineId));
    if (next) {
      const p = sellable.find((p) => p.productId === next.productId);
      setLines((prev) => [...prev, {
        salesOrderLineId: next.lineId,
        productId: next.productId,
        shippedQuantity: next.orderedQuantity,
        unitCost: p?.standardCost ?? "0",
      }]);
      return;
    }
    if (!firstFinished) return;
    setLines((prev) => [...prev, {
      salesOrderLineId: "",
      productId: firstFinished.productId,
      shippedQuantity: "1",
      unitCost: firstFinished.standardCost,
    }]);
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const apiLines: PostShipmentLine[] = lines.map((l) => {
        const p = sellable.find((p) => p.productId === l.productId);
        if (!p) throw new Error(`Unknown product ${l.productId}`);
        return {
          salesOrderLineId: l.salesOrderLineId || undefined,
          productId: p.productId,
          productSku: p.sku,
          productName: p.name,
          shippedQuantity: l.shippedQuantity,
          unitCost: l.unitCost,
        };
      });
      const result = await postShipment({
        shipmentNumber,
        salesOrderHeaderId,
        warehouseCode,
        lines: apiLines,
      });
      setSubmit({ status: "success", message: `posted ${result.id ?? shipmentNumber}` });
      setShipmentNumber(`SH-${Date.now()}`);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Post shipment" persona={<PersonaTag {...persona} />}>
      <p className="mb-4 text-sm text-text-muted">
        Ship goods against a fulfilment-ready sales order. The fulfilment saga advances
        <span className="font-mono"> ready_to_ship → goods_shipped</span>; finance auto-creates the customer invoice;
        inventory decrements on-hand and releases the reservation.
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <FieldRow label="Shipment number" required>
          <Input value={shipmentNumber} onChange={(e) => setShipmentNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Sales order" required hint={
          readyToShip.length === 0
            ? "no orders are ready_to_ship yet — drive an order through the saga first"
            : `${readyToShip.length} ready_to_ship order${readyToShip.length === 1 ? "" : "s"}`
        }>
          <Select value={salesOrderHeaderId} onChange={(e) => setSalesOrderHeaderId(e.target.value)}>
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
              <th className="py-2 pr-3 font-semibold">SO line id (optional)</th>
              <th className="py-2 pr-3 text-right font-semibold">Shipped qty</th>
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
                      const p = sellable.find((p) => p.productId === e.target.value);
                      updateLine(idx, {
                        productId: e.target.value,
                        unitCost: p?.standardCost ?? line.unitCost,
                      });
                    }}
                  >
                    {sellable.map((p) => (
                      <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                    ))}
                  </Select>
                </td>
                <td className="py-2 pr-3">
                  <Input
                    placeholder="optional"
                    value={line.salesOrderLineId}
                    onChange={(e) => updateLine(idx, { salesOrderLineId: e.target.value })}
                  />
                </td>
                <td className="py-2 pr-3">
                  <Input
                    type="number"
                    step="0.01"
                    min="0.01"
                    className="text-right"
                    value={line.shippedQuantity}
                    onChange={(e) => updateLine(idx, { shippedQuantity: e.target.value })}
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
          disabled={submit.status === "submitting" || lines.length === 0 || !salesOrderHeaderId}
        >
          Post shipment
        </Button>
      </div>
    </FormCard>
  );
}
