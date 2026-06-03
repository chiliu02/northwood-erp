import { useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Pencil, ShieldCheck } from "lucide-react";
import { apiGet, apiPut, apiPatch, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { DetailLayout } from "@/components/ui/DetailLayout";
import { ActionButton } from "@/components/ui/ActionButton";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { FormSection, ReadOnlyField, Field } from "@/components/ui/FormSection";
import { TextArea, Select } from "@/components/ui/Form";

interface Supplier {
  supplierId: string;
  supplierCode: string;
  name: string;
  email: string | null;
  phone: string | null;
  address: string | null;
  status: string;
  version: number;
}

type DialogKind = "details" | "status" | null;

const STATUSES = ["active", "inactive", "blocked"];

function statusTone(status: string): "success" | "neutral" | "error" {
  if (status === "active") return "success";
  if (status === "blocked") return "error";
  return "neutral";
}

/**
 * Supplier master detail page — mirrors CustomerDetail. Two authoring dialogs:
 * edit details (name / email / phone / address) and change status
 * (active / inactive / blocked). A blocked or inactive supplier is skipped by
 * PO supplier-selection.
 */
export function SupplierDetail() {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DialogKind>(null);

  const { data, isLoading, error: fetchError } = useQuery({
    queryKey: ["supplier", id],
    queryFn: () => apiGet<Supplier>(`/api/suppliers/${id}`),
    enabled: !!id,
  });

  const close = () => setDialog(null);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ["supplier", id] });
    queryClient.invalidateQueries({ queryKey: ["suppliers"] });
  };

  if (isLoading) {
    return <div className="flex h-full items-center justify-center text-sm text-text-muted">Loading supplier…</div>;
  }
  if (fetchError || !data) {
    return (
      <div className="px-8 py-12">
        <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
          Failed to load supplier: {fetchError instanceof Error ? fetchError.message : "Not found"}
        </div>
      </div>
    );
  }

  return (
    <>
      <DetailLayout
        trail={[
          { label: "Purchasing" },
          { label: "Suppliers", to: "/suppliers" },
          { label: data.supplierCode },
        ]}
        title={data.name}
        subtitle={data.supplierCode}
        status={{ label: data.status, tone: statusTone(data.status) }}
        actions={
          <>
            <ActionButton
              icon={<Pencil className="h-4 w-4" />}
              onClick={() => setDialog("details")}
              requiresRole="purchasing_manager"
            >
              Edit details
            </ActionButton>
            <ActionButton
              icon={<ShieldCheck className="h-4 w-4" />}
              onClick={() => setDialog("status")}
              requiresRole="purchasing_manager"
            >
              Change status
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
                  <ReadOnlyField label="Code" value={<span className="font-medium tabular-nums">{data.supplierCode}</span>} />
                  <ReadOnlyField label="Name" value={data.name} />
                  <ReadOnlyField label="Status" value={data.status} />
                </FormSection>

                <FormSection title="Contact">
                  <ReadOnlyField label="Email" value={data.email ?? "—"} />
                  <ReadOnlyField label="Phone" value={data.phone ?? "—"} />
                </FormSection>

                <FormSection title="Address">
                  <ReadOnlyField label="Address" value={
                    <span className="whitespace-pre-line">{data.address ?? "—"}</span>
                  } />
                </FormSection>
              </div>
            ),
          },
        ]}
      />

      {dialog === "details" && <DetailsDialog supplier={data} onClose={close} onSaved={refresh} />}
      {dialog === "status" && <StatusDialog supplier={data} onClose={close} onSaved={refresh} />}
    </>
  );
}

interface DialogProps {
  supplier: Supplier;
  onClose: () => void;
  onSaved: () => void;
}

function DetailsDialog({ supplier, onClose, onSaved }: DialogProps) {
  const [name, setName] = useState(supplier.name);
  const [email, setEmail] = useState(supplier.email ?? "");
  const [phone, setPhone] = useState(supplier.phone ?? "");
  const [address, setAddress] = useState(supplier.address ?? "");
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => apiPut(`/api/suppliers/${supplier.supplierId}`, {
      name: name.trim(),
      email: email.trim() || null,
      phone: phone.trim() || null,
      address: address.trim() || null,
    }),
    onSuccess: () => {
      toast.success("Supplier details updated.");
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Update failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title="Edit supplier details"
      message={
        <div className="space-y-3">
          <Field label="Name" required>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
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
          <Field label="Address">
            <TextArea value={address} onChange={(e) => setAddress(e.target.value)} rows={3} />
          </Field>
        </div>
      }
      confirmLabel="Save"
      busy={mutation.isPending}
      confirmDisabled={!name.trim()}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}

function StatusDialog({ supplier, onClose, onSaved }: DialogProps) {
  const [status, setStatus] = useState(supplier.status);
  const [reason, setReason] = useState("");
  const toast = useToast();
  const mutation = useMutation({
    mutationFn: () => apiPatch(`/api/suppliers/${supplier.supplierId}/status`, {
      status,
      reason: reason.trim() || null,
    }),
    onSuccess: () => {
      toast.success(`Supplier status changed to ${status}.`);
      onSaved();
      onClose();
    },
    onError: (e) => toast.error(`Status change failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });
  return (
    <ConfirmDialog
      open
      title="Change supplier status"
      message={
        <div className="space-y-3">
          <p className="text-text-muted">
            A <strong>blocked</strong> or <strong>inactive</strong> supplier is skipped by PO supplier-selection.
          </p>
          <Field label="Status" required>
            <Select value={status} onChange={(e) => setStatus(e.target.value)}>
              {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </Select>
          </Field>
          <Field label="Reason">
            <TextArea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} />
          </Field>
        </div>
      }
      confirmLabel="Save"
      busy={mutation.isPending}
      confirmDisabled={status === supplier.status}
      onConfirm={() => mutation.mutate()}
      onCancel={onClose}
    />
  );
}
