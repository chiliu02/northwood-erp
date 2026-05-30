import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, MapPin, Truck, Ban } from "lucide-react";
import { apiGet, apiPost, apiPut, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { TextArea } from "@/components/ui/Form";

interface Customer {
  customerId: string;
  customerCode: string;
  name: string;
  email: string | null;
  phone: string | null;
  billingAddress: string | null;
  shippingAddress: string | null;
  status: string;
  defaultPaymentTerms: string;
  version: number;
}

type DialogKind = "name" | "contact" | "billing" | "shipping" | "deactivate" | null;

/**
 * Customer master detail page. Four authoring dialogs (name, contact,
 * billing address, shipping address) plus deactivate. Snapshot-only policy
 * means renames here do NOT update existing sales orders or the 360 view —
 * see {@code design-notes.md} → "Snapshotted reference data".
 */
export function CustomerDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DialogKind>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["customer", id],
    queryFn: () => apiGet<Customer>(`/api/customers/${id}`),
    enabled: !!id,
  });

  const close = () => setDialog(null);

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading customer…</div>;
  }
  if (fetchError || !data) {
    return (
      <div className="px-8 py-12">
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load customer: {fetchError instanceof Error ? fetchError.message : "Not found"}
        </div>
      </div>
    );
  }

  const isInactive = data.status !== "active";

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Home", to: "/" },
          { label: "Sales" },
          { label: "Customers", to: "/customers" },
          { label: data.customerCode },
        ]}
        title={data.name}
        subtitle={data.customerCode}
        status={{
          label: data.status,
          tone: data.status === "active" ? "success" : data.status === "blocked" ? "error" : "neutral",
        }}
        actions={
          <>
            <ActionButton
              icon={<Pencil className="h-4 w-4" />}
              onClick={() => setDialog("name")}
              requiresRole="sales_clerk"
              disabled={isInactive}
            >
              Rename
            </ActionButton>
            <ActionButton
              icon={<Pencil className="h-4 w-4" />}
              onClick={() => setDialog("contact")}
              requiresRole="sales_clerk"
              disabled={isInactive}
            >
              Contact
            </ActionButton>
            <ActionButton
              icon={<MapPin className="h-4 w-4" />}
              onClick={() => setDialog("billing")}
              requiresRole="sales_clerk"
              disabled={isInactive}
            >
              Billing address
            </ActionButton>
            <ActionButton
              icon={<Truck className="h-4 w-4" />}
              onClick={() => setDialog("shipping")}
              requiresRole="sales_clerk"
              disabled={isInactive}
            >
              Shipping address
            </ActionButton>
            <ActionButton
              variant="danger"
              icon={<Ban className="h-4 w-4" />}
              onClick={() => setDialog("deactivate")}
              requiresRole="sales_clerk"
              disabled={isInactive}
            >
              Deactivate
            </ActionButton>
          </>
        }
        tabs={[
          {
            key: "overview",
            label: "Overview",
            content: (
              <div className="grid gap-4 lg:grid-cols-2">
                <FormSection title="Identity">
                  <ReadOnlyField label="Code" value={<span className="font-medium tabular-nums">{data.customerCode}</span>} />
                  <ReadOnlyField label="Name" value={data.name} />
                  <ReadOnlyField label="Status" value={data.status} />
                  <ReadOnlyField label="Default payment terms" value={<span className="font-mono text-xs">{data.defaultPaymentTerms}</span>} />
                </FormSection>

                <FormSection title="Contact">
                  <ReadOnlyField label="Email" value={data.email ?? "—"} />
                  <ReadOnlyField label="Phone" value={data.phone ?? "—"} />
                </FormSection>

                <FormSection title="Billing address">
                  <ReadOnlyField label="Address" value={
                    <span className="whitespace-pre-line">{data.billingAddress ?? "—"}</span>
                  } />
                </FormSection>

                <FormSection title="Shipping address">
                  <ReadOnlyField label="Address" value={
                    <span className="whitespace-pre-line">{data.shippingAddress ?? "—"}</span>
                  } />
                </FormSection>
              </div>
            ),
          },
        ]}
      />

      {dialog === "name" && (
        <NameDialog
          customer={data}
          onClose={close}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ["customer", id] });
            queryClient.invalidateQueries({ queryKey: ["customers"] });
          }}
        />
      )}
      {dialog === "contact" && (
        <ContactDialog
          customer={data}
          onClose={close}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ["customer", id] })}
        />
      )}
      {dialog === "billing" && (
        <AddressDialog
          customer={data}
          kind="billing"
          onClose={close}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ["customer", id] })}
        />
      )}
      {dialog === "shipping" && (
        <AddressDialog
          customer={data}
          kind="shipping"
          onClose={close}
          onSaved={() => queryClient.invalidateQueries({ queryKey: ["customer", id] })}
        />
      )}
      {dialog === "deactivate" && (
        <DeactivateDialog
          customer={data}
          onClose={close}
          onSaved={() => {
            queryClient.invalidateQueries({ queryKey: ["customer", id] });
            queryClient.invalidateQueries({ queryKey: ["customers"] });
          }}
        />
      )}
    </>
  );
}

interface DialogProps {
  customer: Customer;
  onClose: () => void;
  onSaved: () => void;
}

function NameDialog({ customer, onClose, onSaved }: DialogProps) {
  const [name, setName] = useState(customer.name);
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/customers/${customer.customerId}/name`, { name }),
    onSuccess: () => {
      toast.success("Customer renamed (snapshot-only — historical orders keep the old name).");
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Rename failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title="Rename customer"
      message={
        <div className="space-y-3">
          <p className="text-text-muted">
            Existing orders + reporting views keep the current name; only future orders use the new one.
          </p>
          <Field label="Name" required>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
            />
          </Field>
        </div>
      }
      confirmLabel="Save"
      busy={!name.trim() || name === customer.name || mutation.isPending}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}

function ContactDialog({ customer, onClose, onSaved }: DialogProps) {
  const [email, setEmail] = useState(customer.email ?? "");
  const [phone, setPhone] = useState(customer.phone ?? "");
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/customers/${customer.customerId}/contact`, { email: email || null, phone: phone || null }),
    onSuccess: () => {
      toast.success("Contact updated.");
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Update failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title="Edit contact"
      message={
        <div className="space-y-3">
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
        </div>
      }
      confirmLabel="Save"
      busy={mutation.isPending}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}

interface AddressDialogProps extends DialogProps {
  kind: "billing" | "shipping";
}

function AddressDialog({ customer, kind, onClose, onSaved }: AddressDialogProps) {
  const initial = kind === "billing" ? customer.billingAddress : customer.shippingAddress;
  const [address, setAddress] = useState(initial ?? "");
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/customers/${customer.customerId}/${kind}-address`, { address: address || null }),
    onSuccess: () => {
      toast.success(`${kind === "billing" ? "Billing" : "Shipping"} address updated.`);
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Update failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title={`Edit ${kind} address`}
      message={
        <Field label="Address">
          <TextArea value={address} onChange={(e) => setAddress(e.target.value)} rows={4} />
        </Field>
      }
      confirmLabel="Save"
      busy={mutation.isPending}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}

function DeactivateDialog({ customer, onClose, onSaved }: DialogProps) {
  const [reason, setReason] = useState("");
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () =>
      apiPost(`/api/customers/${customer.customerId}/deactivate`, { reason: reason || null }),
    onSuccess: () => {
      toast.success("Customer deactivated.");
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Deactivate failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title="Deactivate customer"
      message={
        <div className="space-y-3">
          <p className="text-text-muted">
            Soft-delete: status flips to <strong>inactive</strong>. New orders for this customer will be rejected;
            existing orders are unaffected.
          </p>
          <Field label="Reason">
            <TextArea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
          </Field>
        </div>
      }
      confirmLabel="Deactivate"
      busy={mutation.isPending}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}
