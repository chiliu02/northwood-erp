import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { Plus, Trash2 } from "lucide-react";
import { fetchProducts } from "@/api/fetchers";
import { placeSalesOrder } from "@/api/commands";
import type { PlaceOrderLine } from "@/api/types-commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";
import { formatMoney } from "@/lib/utils";

interface DraftLine {
  productId: string;
  orderedQuantity: string;
  unitPrice: string;
}

const SEED_CUSTOMER_CODE = "CUST-001";       // Sydney Home Living, from db/northwood_erp.sql

export function PlaceOrder() {
  const navigate = useNavigate();
  const persona = PERSONAS.sarah;
  const { data: products } = useQuery({ queryKey: ["products"], queryFn: fetchProducts });

  const sellable = (products ?? []).filter((p) => p.status === "active");
  const firstSellable = sellable[0];

  const [orderNumber, setOrderNumber] = useState(() => `SO-${Date.now()}`);
  const [customerCode, setCustomerCode] = useState(SEED_CUSTOMER_CODE);
  const [requestedDeliveryDate, setRequestedDeliveryDate] = useState("");
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [paymentTerms, setPaymentTerms] = useState<"on_shipment" | "prepayment" | "cash_on_delivery">("on_shipment");
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Seed first line once products load.
  if (lines.length === 0 && firstSellable) {
    setLines([{
      productId: firstSellable.productId,
      orderedQuantity: "1",
      unitPrice: firstSellable.salesPrice,
    }]);
  }

  const total = lines.reduce(
    (acc, l) => acc + Number(l.orderedQuantity || 0) * Number(l.unitPrice || 0),
    0
  );

  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }

  function addLine() {
    if (!firstSellable) return;
    setLines((prev) => [...prev, {
      productId: firstSellable.productId,
      orderedQuantity: "1",
      unitPrice: firstSellable.salesPrice,
    }]);
  }

  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      const apiLines: PlaceOrderLine[] = lines.map((l) => {
        const product = sellable.find((p) => p.productId === l.productId);
        if (!product) throw new Error(`Unknown product ${l.productId}`);
        return {
          productId: product.productId,
          productSku: product.sku,
          productName: product.name,
          orderedQuantity: l.orderedQuantity,
          unitPrice: l.unitPrice,
        };
      });
      const result = await placeSalesOrder({
        orderNumber,
        customerCode,
        requestedDeliveryDate: requestedDeliveryDate || null,
        currencyCode,
        paymentTerms,
        lines: apiLines,
      });
      setSubmit({ status: "success", message: `placed ${result.orderNumber ?? orderNumber}` });
      // Navigate to the saga console — that's where you'll watch it advance.
      setTimeout(() => navigate("/saga-console"), 700);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard
      title="Place new order"
      persona={<PersonaTag {...persona} />}
    >
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <FieldRow label="Order number" required>
          <Input value={orderNumber} onChange={(e) => setOrderNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Customer code" required hint="Seed customer is CUST-001 (Sydney Home Living)">
          <Input value={customerCode} onChange={(e) => setCustomerCode(e.target.value)} />
        </FieldRow>
        <FieldRow label="Requested delivery date" hint="optional — ISO date">
          <Input type="date" value={requestedDeliveryDate} onChange={(e) => setRequestedDeliveryDate(e.target.value)} />
        </FieldRow>
        <FieldRow label="Currency" required>
          <Select value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value)}>
            <option value="AUD">AUD</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
          </Select>
        </FieldRow>
        <FieldRow label="Payment terms" required hint="On shipment = bill on dispatch (Northwood's credit-AR default). Prepayment = cash with order (invoice + pay before dispatch). COD = cash on delivery (invoice + payment auto-recorded at shipment).">
          <Select
            value={paymentTerms}
            onChange={(e) => setPaymentTerms(e.target.value as "on_shipment" | "prepayment" | "cash_on_delivery")}
          >
            <option value="on_shipment">on_shipment</option>
            <option value="prepayment">prepayment</option>
            <option value="cash_on_delivery">cash_on_delivery</option>
          </Select>
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
              <th className="py-2 pr-3 text-right font-semibold">Qty</th>
              <th className="py-2 pr-3 text-right font-semibold">Unit price</th>
              <th className="py-2 pr-3 text-right font-semibold">Line total</th>
              <th className="py-2 font-semibold"></th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, idx) => {
              const lineTotal = Number(line.orderedQuantity) * Number(line.unitPrice);
              return (
                <tr key={idx} className="border-b border-border-subtle last:border-b-0">
                  <td className="py-2 pr-3">
                    <Select
                      value={line.productId}
                      onChange={(e) => {
                        const p = sellable.find((p) => p.productId === e.target.value);
                        updateLine(idx, {
                          productId: e.target.value,
                          unitPrice: p?.salesPrice ?? line.unitPrice,
                        });
                      }}
                    >
                      {sellable.map((p) => (
                        <option key={p.productId} value={p.productId}>
                          {p.sku} · {p.name}
                        </option>
                      ))}
                    </Select>
                  </td>
                  <td className="py-2 pr-3">
                    <Input
                      type="number"
                      step="0.01"
                      min="0.01"
                      className="text-right"
                      value={line.orderedQuantity}
                      onChange={(e) => updateLine(idx, { orderedQuantity: e.target.value })}
                    />
                  </td>
                  <td className="py-2 pr-3">
                    <Input
                      type="number"
                      step="0.01"
                      min="0"
                      className="text-right"
                      value={line.unitPrice}
                      onChange={(e) => updateLine(idx, { unitPrice: e.target.value })}
                    />
                  </td>
                  <td className="py-2 pr-3 text-right tabular-nums">
                    {formatMoney(isNaN(lineTotal) ? 0 : lineTotal, currencyCode)}
                  </td>
                  <td className="py-2 text-right">
                    <Button variant="ghost" onClick={() => removeLine(idx)} disabled={lines.length === 1}>
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={3} className="py-3 pr-3 text-right text-xs uppercase tracking-wider text-text-muted">Total</td>
              <td className="py-3 pr-3 text-right text-base font-semibold tabular-nums">
                {formatMoney(total, currencyCode)}
              </td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>

      <div className="mt-6 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="primary"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || lines.length === 0}
        >
          Place order
        </Button>
      </div>
    </FormCard>
  );
}
