import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X } from "lucide-react";
import { apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";
import { TextArea } from "@/components/ui/Form";

interface CreatedCustomer {
  customerId: string;
}

/** Register-customer form. Sales clerk authoring path. */
export function CustomerNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [customerCode, setCustomerCode] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [billingAddress, setBillingAddress] = useState("");
  const [shippingAddress, setShippingAddress] = useState("");
  const [defaultPaymentTerms, setDefaultPaymentTerms] = useState<"on_shipment" | "prepayment" | "cash_on_delivery" | "deposit">("on_shipment");

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedCustomer>("/api/customers", {
      customerCode: customerCode.trim(),
      name: name.trim(),
      email: email.trim() || null,
      phone: phone.trim() || null,
      billingAddress: billingAddress.trim() || null,
      shippingAddress: shippingAddress.trim() || null,
      defaultPaymentTerms,
    }),
    onSuccess: (created) => {
      toast.success(`Customer ${customerCode.trim()} created.`);
      queryClient.invalidateQueries({ queryKey: ["customers"] });
      navigate(`/customers/${created.customerId}`);
    },
    onError: (e) =>
      toast.error(`Create failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave = customerCode.trim().length > 0 && name.trim().length > 0 && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="New customer"
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "Customers", to: "/customers" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/customers")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="sales_clerk"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Create
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <div className="grid gap-4 lg:grid-cols-2 max-w-4xl">
          <FormSection title="Identity">
            <Field label="Code" required>
              <input
                type="text"
                value={customerCode}
                onChange={(e) => setCustomerCode(e.target.value)}
                placeholder="CUST-002"
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Name" required>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>

          <FormSection title="Contact">
            <Field label="Email">
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Phone">
              <input
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>

          <FormSection title="Commercial terms">
            <Field
              label="Default payment terms"
              required
              hint="Snapshotted onto every new order for this customer at placement (overridable per order). on_shipment = credit (bill on dispatch). prepayment = cash with order. cod = cash on delivery (auto-settled at shipment). deposit = part-payment up front, balance at shipment (default 50%; set the % per order)."
            >
              <select
                value={defaultPaymentTerms}
                onChange={(e) => setDefaultPaymentTerms(e.target.value as "on_shipment" | "prepayment" | "cash_on_delivery" | "deposit")}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="on_shipment">on_shipment</option>
                <option value="prepayment">prepayment</option>
                <option value="cash_on_delivery">cash_on_delivery</option>
                <option value="deposit">deposit</option>
              </select>
            </Field>
          </FormSection>

          <FormSection title="Billing address">
            <Field label="Address">
              <TextArea value={billingAddress} onChange={(e) => setBillingAddress(e.target.value)} rows={3} />
            </Field>
          </FormSection>

          <FormSection title="Shipping address">
            <Field label="Address">
              <TextArea value={shippingAddress} onChange={(e) => setShippingAddress(e.target.value)} rows={3} />
            </Field>
          </FormSection>
        </div>
      </div>
    </>
  );
}
