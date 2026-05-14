import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";
import clsx from "clsx";

type Side = "supplier" | "customer";

type PaymentMethod = "bank_transfer" | "cash" | "card" | "cheque";

interface SupplierInvoiceRow {
  id: string;
  internalInvoiceNumber: string;
  supplierName: string;
  currencyCode: string;
  totalAmount: string;
  status: string;
}

interface CustomerInvoiceRow {
  id: string;
  invoiceNumber: string;
  customerName: string;
  currencyCode: string;
  totalAmount: string;
  status: string;
}

interface CreatedPayment {
  id: string;
}

/** Record-payment form. Two-tab page: AP (supplier) + AR (customer). */
export function PaymentNew() {
  const navigate = useNavigate();
  const [side, setSide] = useState<Side>("supplier");

  return (
    <>
      <PageHeader
        title="Record payment"
        trail={[
          { label: "Home", to: "/" },
          { label: "Finance" },
          { label: "Payments", to: "/payments" },
          { label: "New" },
        ]}
        actions={
          <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/payments")}>
            Cancel
          </ActionButton>
        }
      />

      <div className="px-8 py-6">
        <div className="mb-4 inline-flex rounded-md border border-border-default p-0.5">
          <Tab active={side === "supplier"} onClick={() => setSide("supplier")} label="Supplier (AP)" />
          <Tab active={side === "customer"} onClick={() => setSide("customer")} label="Customer (AR)" />
        </div>

        {side === "supplier" ? <SupplierPaymentForm /> : <CustomerPaymentForm />}
      </div>
    </>
  );
}

function Tab({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={clsx(
        "rounded px-3 py-1 text-xs font-medium uppercase tracking-wider transition-colors",
        active ? "bg-bg-subtle text-text-primary" : "text-text-muted hover:text-text-primary"
      )}
    >
      {label}
    </button>
  );
}

function SupplierPaymentForm() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: invoices } = useQuery({
    queryKey: ["supplier-invoices"],
    queryFn: () => apiGet<SupplierInvoiceRow[]>("/api/supplier-invoices"),
    refetchInterval: 5_000,
  });

  const allInvoices = (invoices ?? []).slice().sort((a, b) =>
    a.internalInvoiceNumber.localeCompare(b.internalInvoiceNumber)
  );
  const approved = allInvoices.filter((i) => i.status === "approved");
  const otherStates = allInvoices.filter((i) => i.status !== "approved");

  const [paymentNumber, setPaymentNumber] = useState(() => `PMT-${Date.now()}`);
  const [supplierInvoiceHeaderId, setSupplierInvoiceHeaderId] = useState("");
  const [amount, setAmount] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("bank_transfer");
  const [paymentDate, setPaymentDate] = useState("");

  // Auto-select the newest approved invoice + default amount to its total.
  useEffect(() => {
    if (!supplierInvoiceHeaderId && approved.length > 0) {
      const inv = approved[approved.length - 1];
      setSupplierInvoiceHeaderId(inv.id);
      setAmount(inv.totalAmount);
    }
  }, [approved, supplierInvoiceHeaderId]);

  function onPickInvoice(id: string) {
    setSupplierInvoiceHeaderId(id);
    const inv = allInvoices.find((i) => i.id === id);
    if (inv) setAmount(inv.totalAmount);
  }

  const selected = allInvoices.find((i) => i.id === supplierInvoiceHeaderId);

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedPayment>("/api/payments", {
      paymentNumber: paymentNumber.trim(),
      supplierInvoiceHeaderId,
      amount,
      paymentMethod,
      paymentDate: paymentDate || null,
    }),
    onSuccess: () => {
      toast.success(`Supplier payment ${paymentNumber.trim()} recorded.`);
      queryClient.invalidateQueries({ queryKey: ["payments"] });
      queryClient.invalidateQueries({ queryKey: ["supplier-invoices"] });
      queryClient.invalidateQueries({ queryKey: ["purchase-orders"] });
      navigate("/payments");
    },
    onError: (e) =>
      toast.error(`Record failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    paymentNumber.trim().length > 0
    && supplierInvoiceHeaderId.length > 0
    && Number(amount) > 0
    && !mutation.isPending;

  return (
    <>
      <p className="mb-4 max-w-3xl text-sm text-text-muted">
        Pay an approved supplier invoice. On full settlement the P2P saga reaches{" "}
        <code>completed</code> and the PO header flips to <code>paid</code>.
        Partial settlements park the saga at <code>supplier_payment_made</code>.
      </p>

      <div className="grid gap-4 max-w-4xl">
        <div className="grid gap-4 lg:grid-cols-2">
          <FormSection title="Identity">
            <Field label="Payment number" required>
              <input
                type="text"
                value={paymentNumber}
                onChange={(e) => setPaymentNumber(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Supplier invoice" required hint={
              approved.length === 0
                ? "no approved invoices — record + 3-way-match-approve a supplier invoice first"
                : `${approved.length} approved invoice${approved.length === 1 ? "" : "s"}`
            }>
              <select
                value={supplierInvoiceHeaderId}
                onChange={(e) => onPickInvoice(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="">— pick an invoice —</option>
                {approved.length > 0 && (
                  <optgroup label="Approved">
                    {approved.map((i) => (
                      <option key={i.id} value={i.id}>
                        {i.internalInvoiceNumber} · {i.supplierName} · {formatMoney(i.totalAmount)} {i.currencyCode}
                      </option>
                    ))}
                  </optgroup>
                )}
                {otherStates.length > 0 && (
                  <optgroup label="Other status (advanced)">
                    {otherStates.map((i) => (
                      <option key={i.id} value={i.id}>
                        {i.internalInvoiceNumber} · {i.supplierName} · {formatMoney(i.totalAmount)} {i.currencyCode} · {i.status}
                      </option>
                    ))}
                  </optgroup>
                )}
              </select>
            </Field>
          </FormSection>

          <FormSection title="Payment">
            <Field label="Amount" required hint={
              selected ? `invoice total ${formatMoney(selected.totalAmount)} ${selected.currencyCode}` : undefined
            }>
              <input
                type="number" step="0.01" min="0.01"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Method" required>
              <select
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="bank_transfer">Bank transfer</option>
                <option value="cash">Cash</option>
                <option value="card">Card</option>
                <option value="cheque">Cheque</option>
              </select>
            </Field>
            <Field label="Payment date" hint="optional — defaults to today">
              <input
                type="date"
                value={paymentDate}
                onChange={(e) => setPaymentDate(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>
        </div>

        <div className="flex items-center justify-end gap-3">
          <ActionButton
            variant="primary"
            icon={<Save className="h-4 w-4" />}
            requiresRole="accountant"
            disabled={!canSave}
            onClick={() => mutation.mutate()}
          >
            Record supplier payment
          </ActionButton>
        </div>
      </div>
    </>
  );
}

function CustomerPaymentForm() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: invoices } = useQuery({
    queryKey: ["customer-invoices"],
    queryFn: () => apiGet<CustomerInvoiceRow[]>("/api/customer-invoices"),
    refetchInterval: 5_000,
  });

  const allInvoices = (invoices ?? []).slice().sort((a, b) =>
    a.invoiceNumber.localeCompare(b.invoiceNumber)
  );
  const openInvoices = allInvoices.filter((i) => i.status !== "paid");
  const paidInvoices = allInvoices.filter((i) => i.status === "paid");

  const [paymentNumber, setPaymentNumber] = useState(() => `PMT-${Date.now()}`);
  const [customerInvoiceHeaderId, setCustomerInvoiceHeaderId] = useState("");
  const [amount, setAmount] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("bank_transfer");
  const [paymentDate, setPaymentDate] = useState("");

  useEffect(() => {
    if (!customerInvoiceHeaderId && openInvoices.length > 0) {
      const inv = openInvoices[openInvoices.length - 1];
      setCustomerInvoiceHeaderId(inv.id);
      setAmount(inv.totalAmount);
    }
  }, [openInvoices, customerInvoiceHeaderId]);

  function onPickInvoice(id: string) {
    setCustomerInvoiceHeaderId(id);
    const inv = allInvoices.find((i) => i.id === id);
    if (inv) setAmount(inv.totalAmount);
  }

  const selected = allInvoices.find((i) => i.id === customerInvoiceHeaderId);

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedPayment>("/api/payments/customer", {
      paymentNumber: paymentNumber.trim(),
      customerInvoiceHeaderId,
      amount,
      paymentMethod,
      paymentDate: paymentDate || null,
    }),
    onSuccess: () => {
      toast.success(`Customer payment ${paymentNumber.trim()} recorded.`);
      queryClient.invalidateQueries({ queryKey: ["payments"] });
      queryClient.invalidateQueries({ queryKey: ["customer-invoices"] });
      queryClient.invalidateQueries({ queryKey: ["sales-orders"] });
      navigate("/payments");
    },
    onError: (e) =>
      toast.error(`Record failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave =
    paymentNumber.trim().length > 0
    && customerInvoiceHeaderId.length > 0
    && Number(amount) > 0
    && !mutation.isPending;

  return (
    <>
      <p className="mb-4 max-w-3xl text-sm text-text-muted">
        Record an incoming customer payment. On full settlement the fulfilment saga reaches{" "}
        <code>completed</code> and the order header flips to <code>completed</code>.
        Partial settlements transition to <code>invoice_paid</code> and park.
      </p>

      <div className="grid gap-4 max-w-4xl">
        <div className="grid gap-4 lg:grid-cols-2">
          <FormSection title="Identity">
            <Field label="Payment number" required>
              <input
                type="text"
                value={paymentNumber}
                onChange={(e) => setPaymentNumber(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Customer invoice" required hint={
              openInvoices.length === 0
                ? "no open invoices — ship a sales order first to auto-create one"
                : `${openInvoices.length} open invoice${openInvoices.length === 1 ? "" : "s"}`
            }>
              <select
                value={customerInvoiceHeaderId}
                onChange={(e) => onPickInvoice(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="">— pick an invoice —</option>
                {openInvoices.length > 0 && (
                  <optgroup label="Open">
                    {openInvoices.map((i) => (
                      <option key={i.id} value={i.id}>
                        {i.invoiceNumber} · {i.customerName} · {formatMoney(i.totalAmount)} {i.currencyCode}
                      </option>
                    ))}
                  </optgroup>
                )}
                {paidInvoices.length > 0 && (
                  <optgroup label="Already paid">
                    {paidInvoices.map((i) => (
                      <option key={i.id} value={i.id}>
                        {i.invoiceNumber} · {i.customerName} · {formatMoney(i.totalAmount)} {i.currencyCode} · paid
                      </option>
                    ))}
                  </optgroup>
                )}
              </select>
            </Field>
          </FormSection>

          <FormSection title="Payment">
            <Field label="Amount" required hint={
              selected ? `invoice total ${formatMoney(selected.totalAmount)} ${selected.currencyCode}` : undefined
            }>
              <input
                type="number" step="0.01" min="0.01"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Method" required>
              <select
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="bank_transfer">Bank transfer</option>
                <option value="cash">Cash</option>
                <option value="card">Card</option>
                <option value="cheque">Cheque</option>
              </select>
            </Field>
            <Field label="Payment date" hint="optional — defaults to today">
              <input
                type="date"
                value={paymentDate}
                onChange={(e) => setPaymentDate(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>
        </div>

        <div className="flex items-center justify-end gap-3">
          <ActionButton
            variant="primary"
            icon={<Save className="h-4 w-4" />}
            requiresRole="accountant"
            disabled={!canSave}
            onClick={() => mutation.mutate()}
          >
            Record customer payment
          </ActionButton>
        </div>
      </div>
    </>
  );
}

function formatMoney(v: string | null | undefined): string {
  if (v == null) return "—";
  const n = Number(v);
  return Number.isNaN(n) ? String(v) : n.toLocaleString("en-AU", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
