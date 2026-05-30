import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchCustomerInvoices, fetchSupplierInvoices } from "@/api/fetchers";
import { recordSupplierPayment, recordCustomerPayment } from "@/api/commands";
import type { PaymentMethod } from "@/api/types-commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";
import { cn, formatMoney } from "@/lib/utils";

type Side = "supplier" | "customer";

export function Payments() {
  const persona = PERSONAS.olivia;
  const [side, setSide] = useState<Side>("supplier");

  return (
    <FormCard title="Payments" persona={<PersonaTag {...persona} />}>
      <div className="mb-4 inline-flex rounded-md border border-border-subtle p-0.5">
        <Tab active={side === "supplier"} onClick={() => setSide("supplier")} label="Supplier (AP)" />
        <Tab active={side === "customer"} onClick={() => setSide("customer")} label="Customer (AR)" />
      </div>

      {side === "supplier" ? <SupplierPaymentForm /> : <CustomerPaymentForm />}
    </FormCard>
  );
}

function Tab({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "rounded px-3 py-1 text-xs font-medium uppercase tracking-wider transition-colors",
        active ? "bg-bg-hover text-text-primary" : "text-text-muted hover:text-text-primary"
      )}
    >
      {label}
    </button>
  );
}

function SupplierPaymentForm() {
  const { data: invoices } = useQuery({
    queryKey: ["supplier-invoices"],
    queryFn: fetchSupplierInvoices,
    refetchInterval: 5_000,
  });

  // Approved invoices are pay-able (the demo has no `paid` status on supplier
  // invoices in the View — paid_amount lives on a DB column maintained by the
  // allocation trigger, surfaced via findPaymentSnapshot but not exposed on
  // the list endpoint). Default approach: show approved + partially-paid +
  // paid in distinct optgroups; user can pick from approved by default.
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
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Auto-select most recent approved invoice + default amount to its total.
  useEffect(() => {
    if (!supplierInvoiceHeaderId && approved[0]) {
      const inv = approved[approved.length - 1];   // newest by invoice number
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

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      await recordSupplierPayment({
        paymentNumber, supplierInvoiceHeaderId, amount, paymentMethod,
        paymentDate: paymentDate || undefined,
      });
      setSubmit({ status: "success", message: `paid ${formatMoney(amount, selected?.currencyCode ?? "AUD")}` });
      setPaymentNumber(`PMT-${Date.now()}`);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <>
      <p className="mb-4 text-sm text-text-muted">
        Pay an approved supplier invoice. On full settlement the P2P saga reaches
        <span className="font-mono"> completed</span> and the PO header flips to <span className="font-mono">paid</span>.
      </p>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <FieldRow label="Payment number" required>
          <Input value={paymentNumber} onChange={(e) => setPaymentNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Supplier invoice" required hint={
          approved.length === 0
            ? "no approved invoices — record + auto-approve a supplier invoice first"
            : `${approved.length} approved invoice${approved.length === 1 ? "" : "s"}`
        }>
          <Select value={supplierInvoiceHeaderId} onChange={(e) => onPickInvoice(e.target.value)}>
            <option value="">— pick an invoice —</option>
            {approved.length > 0 && (
              <optgroup label="Approved">
                {approved.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.internalInvoiceNumber} · {i.supplierName} · {formatMoney(i.totalAmount, i.currencyCode)}
                  </option>
                ))}
              </optgroup>
            )}
            {otherStates.length > 0 && (
              <optgroup label="Other status (advanced)">
                {otherStates.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.internalInvoiceNumber} · {i.supplierName} · {formatMoney(i.totalAmount, i.currencyCode)} · {i.status}
                  </option>
                ))}
              </optgroup>
            )}
          </Select>
        </FieldRow>
        <FieldRow label="Amount" required hint={
          selected ? `invoice total ${formatMoney(selected.totalAmount, selected.currencyCode)}` : undefined
        }>
          <Input type="number" step="0.01" min="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </FieldRow>
        <FieldRow label="Payment method" required>
          <Select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}>
            <option value="bank_transfer">Bank transfer</option>
            <option value="cash">Cash</option>
            <option value="card">Card</option>
            <option value="cheque">Cheque</option>
          </Select>
        </FieldRow>
        <FieldRow label="Payment date" hint="optional — defaults to today">
          <Input type="date" value={paymentDate} onChange={(e) => setPaymentDate(e.target.value)} />
        </FieldRow>
      </div>
      <div className="mt-6 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="primary"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || !supplierInvoiceHeaderId || !amount}
        >
          Record supplier payment
        </Button>
      </div>
    </>
  );
}

function CustomerPaymentForm() {
  const { data: invoices } = useQuery({
    queryKey: ["customer-invoices"],
    queryFn: fetchCustomerInvoices,
    refetchInterval: 5_000,
  });

  // Show open invoices first, paid ones last (and de-emphasised in the dropdown).
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
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  // Auto-select most recent open invoice + default amount to its total.
  useEffect(() => {
    if (!customerInvoiceHeaderId && openInvoices[0]) {
      const inv = openInvoices[openInvoices.length - 1];   // newest by invoice number
      setCustomerInvoiceHeaderId(inv.id);
      setAmount(inv.totalAmount);
    }
  }, [openInvoices, customerInvoiceHeaderId]);

  // When the user picks a different invoice, prefill amount.
  function onPickInvoice(id: string) {
    setCustomerInvoiceHeaderId(id);
    const inv = allInvoices.find((i) => i.id === id);
    if (inv) setAmount(inv.totalAmount);
  }

  const selected = allInvoices.find((i) => i.id === customerInvoiceHeaderId);

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      await recordCustomerPayment({
        paymentNumber, customerInvoiceHeaderId, amount, paymentMethod,
        paymentDate: paymentDate || undefined,
      });
      setSubmit({ status: "success", message: `received ${formatMoney(amount, selected?.currencyCode ?? "AUD")}` });
      setPaymentNumber(`PMT-${Date.now()}`);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <>
      <p className="mb-4 text-sm text-text-muted">
        Record an incoming customer payment. On full settlement the fulfilment saga reaches
        <span className="font-mono"> completed</span> and the order header flips to <span className="font-mono">completed</span>.
        Partial settlements transition to <span className="font-mono">invoice_partially_paid</span> and park.
      </p>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <FieldRow label="Payment number" required>
          <Input value={paymentNumber} onChange={(e) => setPaymentNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Customer invoice" required hint={
          openInvoices.length === 0
            ? "no open invoices — ship a sales order first to auto-create one"
            : `${openInvoices.length} open invoice${openInvoices.length === 1 ? "" : "s"}`
        }>
          <Select value={customerInvoiceHeaderId} onChange={(e) => onPickInvoice(e.target.value)}>
            <option value="">— pick an invoice —</option>
            {openInvoices.length > 0 && (
              <optgroup label="Open">
                {openInvoices.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.invoiceNumber} · {i.customerName} · {formatMoney(i.totalAmount, i.currencyCode)}
                  </option>
                ))}
              </optgroup>
            )}
            {paidInvoices.length > 0 && (
              <optgroup label="Already paid">
                {paidInvoices.map((i) => (
                  <option key={i.id} value={i.id}>
                    {i.invoiceNumber} · {i.customerName} · {formatMoney(i.totalAmount, i.currencyCode)} · paid
                  </option>
                ))}
              </optgroup>
            )}
          </Select>
        </FieldRow>
        <FieldRow label="Amount" required hint={
          selected ? `invoice total ${formatMoney(selected.totalAmount, selected.currencyCode)}` : undefined
        }>
          <Input type="number" step="0.01" min="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </FieldRow>
        <FieldRow label="Payment method" required>
          <Select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value as PaymentMethod)}>
            <option value="bank_transfer">Bank transfer</option>
            <option value="cash">Cash</option>
            <option value="card">Card</option>
            <option value="cheque">Cheque</option>
          </Select>
        </FieldRow>
        <FieldRow label="Payment date" hint="optional — defaults to today">
          <Input type="date" value={paymentDate} onChange={(e) => setPaymentDate(e.target.value)} />
        </FieldRow>
      </div>
      <div className="mt-6 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button
          variant="primary"
          onClick={onSubmit}
          disabled={submit.status === "submitting" || !customerInvoiceHeaderId || !amount}
        >
          Record customer payment
        </Button>
      </div>
    </>
  );
}
