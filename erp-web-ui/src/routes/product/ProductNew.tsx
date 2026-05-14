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

interface CreatedProduct {
  productId: string;
}

// Seed UoMs from db/northwood_erp.sql §0 — hardcoded because no UoM
// admin page exists yet (the catalog is a closed list in the demo).
const UOMS: { id: string; code: string; label: string }[] = [
  { id: "00000000-0000-7000-8000-000000000010", code: "EA", label: "EA — each" },
  { id: "00000000-0000-7000-8000-000000000011", code: "L",  label: "L — litre" },
  { id: "00000000-0000-7000-8000-000000000012", code: "KG", label: "KG — kilogram" },
];

const PRODUCT_TYPES: { value: string; label: string }[] = [
  { value: "finished_good",      label: "Finished good" },
  { value: "raw_material",       label: "Raw material" },
  { value: "semi_finished_good", label: "Semi-finished good" },
  { value: "service",            label: "Service" },
];

/** Register-product form. Catalog manager authoring path. */
export function ProductNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [sku, setSku] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [productType, setProductType] = useState(PRODUCT_TYPES[0].value);
  const [baseUomId, setBaseUomId] = useState(UOMS[0].id);
  const [salesPrice, setSalesPrice] = useState("0");
  const [standardCost, setStandardCost] = useState("0");
  const [currencyCode, setCurrencyCode] = useState("AUD");

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedProduct>("/api/products", {
      sku: sku.trim(),
      name: name.trim(),
      description: description.trim() || null,
      productType,
      baseUomId,
      salesPrice,
      standardCost,
      currencyCode: currencyCode.trim().toUpperCase(),
    }),
    onSuccess: (created) => {
      toast.success(`Product ${sku.trim()} registered.`);
      queryClient.invalidateQueries({ queryKey: ["products"] });
      navigate(`/products/${created.productId}`);
    },
    onError: (e) =>
      toast.error(`Create failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  const priceOk = (v: string) => v.trim() !== "" && !Number.isNaN(Number(v)) && Number(v) >= 0;
  const canSave =
    sku.trim().length > 0
    && name.trim().length > 0
    && priceOk(salesPrice)
    && priceOk(standardCost)
    && currencyCode.trim().length === 3
    && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="New product"
        trail={[
          { label: "Home", to: "/" },
          { label: "Master Data" },
          { label: "Products", to: "/products" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/products")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="catalog_manager"
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
            <Field label="SKU" required>
              <input
                type="text"
                value={sku}
                onChange={(e) => setSku(e.target.value)}
                placeholder="FG-DESK-001"
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
            <Field label="Description">
              <TextArea
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={2}
              />
            </Field>
          </FormSection>

          <FormSection title="Classification">
            <Field label="Type" required>
              <select
                value={productType}
                onChange={(e) => setProductType(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                {PRODUCT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </Field>
            <Field label="Base unit of measure" required>
              <select
                value={baseUomId}
                onChange={(e) => setBaseUomId(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                {UOMS.map((u) => (
                  <option key={u.id} value={u.id}>{u.label}</option>
                ))}
              </select>
            </Field>
          </FormSection>

          <FormSection title="Pricing">
            <Field label="Sales price" required>
              <input
                type="number" step="0.01" min="0"
                value={salesPrice}
                onChange={(e) => setSalesPrice(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Standard cost" required>
              <input
                type="number" step="0.01" min="0"
                value={standardCost}
                onChange={(e) => setStandardCost(e.target.value)}
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
          </FormSection>
        </div>
      </div>
    </>
  );
}
