import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Plus, Filter, Download } from "lucide-react";
import { apiGet, apiPost, apiPut, apiPatch, ApiError } from "@/lib/api";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { FilterPanel, useFieldFilters, type FilterField } from "@/components/ui/FilterPanel";
import { ConfirmDialog } from "@/components/ui/ConfirmDialog";
import { Field } from "@/components/ui/FormSection";
import { TextInput, Select } from "@/components/ui/Form";
import { StatusPill } from "@/components/ui/StatusPill";
import { downloadCsv } from "@/lib/csv";

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

const STATUSES = ["active", "inactive", "blocked"];

function statusTone(status: string): "success" | "neutral" | "error" {
  if (status === "active") return "success";
  if (status === "blocked") return "error";
  return "neutral";
}

type Dialog = { mode: "add" } | { mode: "edit"; row: Supplier } | null;

/**
 * Supplier master. Tom's view of the vendors that sell us raw material and
 * components. Filterable + exportable like the other list pages; "New supplier"
 * onboards one and clicking a row edits its details + status.
 */
export function Suppliers() {
  const [dialog, setDialog] = useState<Dialog>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ["suppliers"],
    queryFn: () => apiGet<Supplier[]>("/api/suppliers"),
  });

  const filterFields: FilterField<Supplier>[] = [
    { key: "code", label: "Code", get: (s) => s.supplierCode },
    { key: "name", label: "Name", get: (s) => s.name },
    { key: "email", label: "Email", get: (s) => s.email ?? "" },
    { key: "status", label: "Status", type: "select", get: (s) => s.status },
  ];
  const filter = useFieldFilters(data ?? [], filterFields);

  const columns: Column<Supplier>[] = [
    {
      key: "code",
      header: "Code",
      width: "150px",
      sortAccessor: (s) => s.supplierCode,
      render: (s) => <span className="font-medium tabular-nums">{s.supplierCode}</span>,
    },
    { key: "name", header: "Name", sortAccessor: (s) => s.name, render: (s) => s.name },
    {
      key: "email",
      header: "Email",
      sortAccessor: (s) => s.email ?? "",
      render: (s) => <span className="text-text-muted">{s.email ?? "—"}</span>,
    },
    {
      key: "phone",
      header: "Phone",
      width: "170px",
      sortAccessor: (s) => s.phone ?? "",
      render: (s) => <span className="text-text-muted">{s.phone ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      width: "120px",
      sortAccessor: (s) => s.status,
      render: (s) => <StatusPill label={s.status} tone={statusTone(s.status)} />,
    },
  ];

  return (
    <>
      <PageHeader
        title="Suppliers"
        description="Supplier master. Onboard vendors, edit their details, and change status (active / inactive / blocked). A blocked or inactive supplier is skipped by PO supplier-selection."
        trail={[
          { label: "Home", to: "/" },
          { label: "Purchasing" },
          { label: "Suppliers" },
        ]}
        actions={
          <>
            <ActionButton
              icon={<Filter className="h-4 w-4" />}
              variant={filter.open ? "primary" : "secondary"}
              onClick={filter.toggle}
            >
              Filter
            </ActionButton>
            <ActionButton
              icon={<Download className="h-4 w-4" />}
              onClick={() => downloadCsv("suppliers.csv", filter.filtered)}
              disabled={filter.filtered.length === 0}
            >
              Export
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Plus className="h-4 w-4" />}
              requiresRole="purchasing_manager"
              onClick={() => setDialog({ mode: "add" })}
            >
              New supplier
            </ActionButton>
          </>
        }
      />

      <FilterPanel
        open={filter.open}
        rows={data ?? []}
        fields={filterFields}
        values={filter.values}
        onChange={filter.set}
        onClear={filter.clear}
        onClose={filter.close}
      />

      <div className="px-8 py-6">
        {error ? (
          <div className="rounded-md border border-status-error/30 bg-status-error-soft px-4 py-3 text-sm text-status-error">
            Failed to load suppliers: {(error as Error).message}
          </div>
        ) : (
          <DataGrid
            columns={columns}
            rows={filter.filtered}
            rowKey={(s) => s.supplierId}
            onRowClick={(s) => setDialog({ mode: "edit", row: s })}
            loading={isLoading}
            emptyState={filter.active ? "No suppliers match the filter." : "No suppliers yet. Click 'New supplier' to add one."}
          />
        )}
        {data && (
          <div className="mt-3 text-xs text-text-muted">
            {filter.filtered.length} supplier{filter.filtered.length === 1 ? "" : "s"}
            {filter.active ? ` (filtered from ${data.length})` : ""}.
          </div>
        )}
      </div>

      <SupplierDialog dialog={dialog} onClose={() => setDialog(null)} />
    </>
  );
}

// ----- Add / edit supplier dialog -----

function SupplierDialog({ dialog, onClose }: { dialog: Dialog; onClose: () => void }) {
  const queryClient = useQueryClient();
  const isEdit = dialog?.mode === "edit";
  const row = dialog?.mode === "edit" ? dialog.row : null;

  const [supplierCode, setSupplierCode] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");
  const [status, setStatus] = useState("active");
  const [error, setError] = useState<string | null>(null);
  // Re-seed fields when a different dialog opens (derived-from-props pattern).
  const [seededFor, setSeededFor] = useState<string | null>(null);
  const dialogKey = dialog ? (dialog.mode === "edit" ? dialog.row.supplierId : "add") : null;
  if (dialog && dialogKey !== seededFor) {
    setSeededFor(dialogKey);
    setSupplierCode(row?.supplierCode ?? "");
    setName(row?.name ?? "");
    setEmail(row?.email ?? "");
    setPhone(row?.phone ?? "");
    setAddress(row?.address ?? "");
    setStatus(row?.status ?? "active");
    setError(null);
  }

  const mutation = useMutation({
    mutationFn: async () => {
      if (!isEdit) {
        await apiPost("/api/suppliers", {
          supplierCode: supplierCode.trim(),
          name: name.trim(),
          email: email.trim() || null,
          phone: phone.trim() || null,
          address: address.trim() || null,
        });
        return;
      }
      // Edit: push detail changes, then a status change if it differs.
      const detailsChanged =
        name.trim() !== (row!.name ?? "") ||
        (email.trim() || "") !== (row!.email ?? "") ||
        (phone.trim() || "") !== (row!.phone ?? "") ||
        (address.trim() || "") !== (row!.address ?? "");
      if (detailsChanged) {
        await apiPut(`/api/suppliers/${row!.supplierId}`, {
          name: name.trim(),
          email: email.trim() || null,
          phone: phone.trim() || null,
          address: address.trim() || null,
        });
      }
      if (status !== row!.status) {
        await apiPatch(`/api/suppliers/${row!.supplierId}/status`, { status, reason: null });
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["suppliers"] });
      onClose();
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Save failed."),
  });

  function submit() {
    if (!isEdit && (!supplierCode.trim() || !name.trim())) {
      setError("Supplier code and name are required.");
      return;
    }
    if (isEdit && !name.trim()) {
      setError("Name is required.");
      return;
    }
    setError(null);
    mutation.mutate();
  }

  return (
    <ConfirmDialog
      open={dialog !== null}
      title={isEdit ? "Edit supplier" : "New supplier"}
      message={
        isEdit
          ? <>Edit <strong>{row?.supplierCode}</strong>. Emits <code>SupplierDetailsChanged</code> and/or <code>SupplierStatusChanged</code>; unchanged values are a no-op.</>
          : <>Onboard a new supplier (lands <code>active</code>). The code must be unique.</>
      }
      confirmLabel={isEdit ? "Save" : "Add"}
      busy={mutation.isPending}
      onCancel={onClose}
      onConfirm={submit}
      body={
        <div className="space-y-3">
          <Field label="Supplier code" required>
            <TextInput value={supplierCode} onChange={(e) => setSupplierCode(e.target.value)} disabled={isEdit} placeholder="SUP-007" />
          </Field>
          <Field label="Name" required>
            <TextInput value={name} onChange={(e) => setName(e.target.value)} autoFocus />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Email">
              <TextInput value={email} onChange={(e) => setEmail(e.target.value)} />
            </Field>
            <Field label="Phone">
              <TextInput value={phone} onChange={(e) => setPhone(e.target.value)} />
            </Field>
          </div>
          <Field label="Address">
            <TextInput value={address} onChange={(e) => setAddress(e.target.value)} />
          </Field>
          {isEdit && (
            <Field label="Status">
              <Select value={status} onChange={(e) => setStatus(e.target.value)}>
                {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
              </Select>
            </Field>
          )}
          {error && (
            <div className="rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
              {error}
            </div>
          )}
        </div>
      }
    />
  );
}
