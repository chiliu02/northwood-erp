import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";

interface Customer {
  customerId: string;
  customerCode: string;
  name: string;
  status: string;
  defaultPaymentTerms: string;
}

interface Product {
  productId: string;
  sku: string;
  name: string;
  salesPrice: string;
  status: string;
}

interface CreatedOrder {
  id: string;
}

interface DraftLine {
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice: string;
}

/** Place-sales-order form. Sales clerk authoring path. */
export function SalesOrderNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: customers } = useQuery({
    queryKey: ["customers"],
    queryFn: () => apiGet<Customer[]>("/api/customers"),
  });
  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const activeCustomers = (customers ?? []).filter((c) => c.status === "active");
  const sellableProducts = (products ?? []).filter((p) => p.status === "active");

  const [orderNumber, setOrderNumber] = useState(() => `SO-${Date.now()}`);
  const [customerCode, setCustomerCode] = useState("");
  const [requestedDeliveryDate, setRequestedDeliveryDate] = useState("");
  const [currencyCode, setCurrencyCode] = useState("AUD");
  const [paymentTerms, setPaymentTerms] = useState<"on_shipment" | "prepayment" | "cash_on_delivery" | "deposit">("on_shipment");
  // Tracks whether the user has manually overridden paymentTerms; once true,
  // changing the customer no longer resets it to the customer's default.
  const [paymentTermsOverridden, setPaymentTermsOverridden] = useState(false);
  const [depositPercent, setDepositPercent] = useState("50");
  const [lines, setLines] = useState<DraftLine[]>([]);

  // Snapshot the selected customer's defaultPaymentTerms onto the form so the
  // wire payload posted to /api/sales-cmd matches Slice A.1's server-side
  // "inherit from customer" semantic visually. The user can still override
  // before posting; an explicit override sticks across subsequent customer
  // changes.
  useEffect(() => {
    if (paymentTermsOverridden) return;
    const c = activeCustomers.find((x) => x.customerCode === customerCode);
    if (!c) return;
    if (c.defaultPaymentTerms === "on_shipment" || c.defaultPaymentTerms === "prepayment"
        || c.defaultPaymentTerms === "cash_on_delivery" || c.defaultPaymentTerms === "deposit") {
      setPaymentTerms(c.defaultPaymentTerms);
    }
  }, [customerCode, activeCustomers, paymentTermsOverridden]);

  function addLine() {
    if (sellableProducts.length === 0) return;
    const p = sellableProducts[0];
    setLines((prev) => [...prev, {
      productId: p.productId,
      productSku: p.sku,
      productName: p.name,
      orderedQuantity: "1",
      unitPrice: p.salesPrice,
    }]);
  }
  function updateLine(idx: number, patch: Partial<DraftLine>) {
    setLines((prev) => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }
  function pickProduct(idx: number, productId: string) {
    const p = sellableProducts.find((x) => x.productId === productId);
    if (!p) return;
    updateLine(idx, {
      productId: p.productId,
      productSku: p.sku,
      productName: p.name,
      unitPrice: p.salesPrice,
    });
  }
  function removeLine(idx: number) {
    setLines((prev) => prev.filter((_, i) => i !== idx));
  }

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedOrder>("/api/sales-cmd/sales-orders", {
      orderNumber: orderNumber.trim(),
      customerCode,
      requestedDeliveryDate: requestedDeliveryDate || null,
      currencyCode: currencyCode.trim().toUpperCase(),
      paymentTerms,
      depositPercent: paymentTerms === "deposit" ? depositPercent : null,
      lines: lines.map((l) => ({
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        orderedQuantity: l.orderedQuantity,
        unitPrice: l.unitPrice,
        taxRate: "0",
      })),
    }),
    onSuccess: (created) => {
      toast.success(`Sales order ${orderNumber.trim()} placed.`);
      queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
      navigate(`/sales-orders/${created.id}`);
    },
    onError: (e) =>
      toast.error(`Place order failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    orderNumber.trim().length > 0
    && customerCode.length > 0
    && lines.length > 0
    && lines.every((l) => l.productId && Number(l.orderedQuantity) > 0)
    && currencyCode.trim().length === 3
    && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="New sales order"
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "Sales Orders", to: "/sales-orders" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/sales-orders")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="sales_clerk"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Place order
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <div className="grid gap-4 max-w-5xl">
          <div className="grid gap-4 lg:grid-cols-2">
            <FormSection title="Header">
              <Field label="Order number" required>
                <input
                  type="text"
                  value={orderNumber}
                  onChange={(e) => setOrderNumber(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                />
              </Field>
              <Field label="Customer" required hint={
                activeCustomers.length === 0
                  ? "no active customers — register one first"
                  : `${activeCustomers.length} active customer${activeCustomers.length === 1 ? "" : "s"}`
              }>
                <select
                  value={customerCode}
                  onChange={(e) => setCustomerCode(e.target.value)}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                >
                  <option value="">— pick a customer —</option>
                  {activeCustomers.map((c) => (
                    <option key={c.customerCode} value={c.customerCode}>
                      {c.customerCode} · {c.name}
                    </option>
                  ))}
                </select>
              </Field>
            </FormSection>

            <FormSection title="Terms">
              <Field label="Requested delivery date">
                <input
                  type="date"
                  value={requestedDeliveryDate}
                  onChange={(e) => setRequestedDeliveryDate(e.target.value)}
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
              <Field
                label="Payment terms"
                required
                hint={
                  paymentTermsOverridden
                    ? "Overridden — no longer follows the customer's default."
                    : "Defaulted from the selected customer. Override per order if needed."
                }
              >
                <select
                  value={paymentTerms}
                  onChange={(e) => {
                    setPaymentTerms(e.target.value as "on_shipment" | "prepayment" | "cash_on_delivery" | "deposit");
                    setPaymentTermsOverridden(true);
                  }}
                  className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                >
                  <option value="on_shipment">on_shipment</option>
                  <option value="prepayment">prepayment</option>
                  <option value="cash_on_delivery">cash_on_delivery</option>
                  <option value="deposit">deposit</option>
                </select>
              </Field>
              {paymentTerms === "deposit" && (
                <Field label="Deposit %" required hint="Up-front fraction (0–100); the balance invoices at shipment.">
                  <input
                    type="number"
                    min="1"
                    max="99"
                    value={depositPercent}
                    onChange={(e) => setDepositPercent(e.target.value)}
                    className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                  />
                </Field>
              )}
            </FormSection>
          </div>

          <FormSection title="Lines">
            <div className="flex items-center justify-between">
              <p className="text-xs text-text-muted">
                Pick a product per line; sales price auto-fills from the catalog. Edit per-line if needed.
              </p>
              <ActionButton icon={<Plus className="h-4 w-4" />} onClick={addLine}>
                Add line
              </ActionButton>
            </div>
            <table className="mt-2 w-full text-sm">
              <thead className="border-b border-border-default text-left text-[11px] uppercase tracking-wider text-text-muted">
                <tr>
                  <th className="py-2 pr-3 font-semibold">Product</th>
                  <th className="py-2 pr-3 text-right font-semibold">Quantity</th>
                  <th className="py-2 pr-3 text-right font-semibold">Unit price</th>
                  <th className="py-2 font-semibold"></th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, idx) => (
                  <tr key={idx} className="border-b border-border-default last:border-b-0">
                    <td className="py-2 pr-3">
                      <select
                        value={line.productId}
                        onChange={(e) => pickProduct(idx, e.target.value)}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-2 text-sm focus:border-border-focus focus:outline-none"
                      >
                        {sellableProducts.map((p) => (
                          <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                        ))}
                      </select>
                    </td>
                    <td className="py-2 pr-3">
                      <input
                        type="number" step="0.01" min="0.01"
                        value={line.orderedQuantity}
                        onChange={(e) => updateLine(idx, { orderedQuantity: e.target.value })}
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
                    <td colSpan={4} className="py-6 text-center text-sm text-text-faint">
                      No lines yet. Click "Add line" to start.
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
