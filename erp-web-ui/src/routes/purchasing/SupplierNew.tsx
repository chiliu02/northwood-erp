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

interface CreatedSupplier {
  supplierId: string;
}

/** Onboard-supplier form. Purchasing manager authoring path (mirrors CustomerNew). */
export function SupplierNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [supplierCode, setSupplierCode] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedSupplier>("/api/suppliers", {
      supplierCode: supplierCode.trim(),
      name: name.trim(),
      email: email.trim() || null,
      phone: phone.trim() || null,
      address: address.trim() || null,
    }),
    onSuccess: (created) => {
      toast.success(`Supplier ${supplierCode.trim()} created.`);
      queryClient.invalidateQueries({ queryKey: ["suppliers"] });
      navigate(`/suppliers/${created.supplierId}`);
    },
    onError: (e) => toast.error(`Create failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const canSave = supplierCode.trim().length > 0 && name.trim().length > 0 && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="New supplier"
        trail={[
          { label: "Home", to: "/" },
          { label: "Purchasing" },
          { label: "Suppliers", to: "/suppliers" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/suppliers")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="purchasing_manager"
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
                value={supplierCode}
                onChange={(e) => setSupplierCode(e.target.value)}
                placeholder="SUP-007"
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

          <FormSection title="Address">
            <Field label="Address">
              <TextArea value={address} onChange={(e) => setAddress(e.target.value)} rows={3} />
            </Field>
          </FormSection>
        </div>
      </div>
    </>
  );
}
