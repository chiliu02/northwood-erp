import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Save, X, Plus, Trash2 } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";
import { NumberInput, Select } from "@/components/ui/Form";

interface Product {
  productId: string;
  sku: string;
  name: string;
  productType: string;
  status: string;
}

interface CreatedRequisition {
  id: string;
  requisitionNumber: string;
}

interface DraftLine {
  key: string;
  productId: string;
  productSku: string;
  productName: string;
  requestedQuantity: string;
  requiredDate: string;
}

/**
 * Manual purchase requisition authoring. Tom (purchasing_clerk) picks one
 * or more products and quantities; PR auto-approves at creation per current
 * service shape, then converts to a PO via downstream flow.
 *
 * <p>Source-WO link is informational here — manual PRs aren't shortage-driven
 * (those are auto-created by RawMaterialShortageDetectedHandler), so the
 * field stays blank in this form.
 */
export function PurchaseRequisitionNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const products = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });

  const sourceableProducts = useMemo(
    () => (products.data ?? []).filter(p => p.status !== "discontinued"),
    [products.data]
  );

  const [requisitionNumber, setRequisitionNumber] = useState("");
  const [requestedBy, setRequestedBy] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([blankLine()]);

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedRequisition>("/api/purchase-requisitions", {
      requisitionNumber: requisitionNumber.trim(),
      requestedBy: requestedBy.trim() || null,
      lines: lines.map(l => ({
        productId: l.productId,
        productSku: l.productSku,
        productName: l.productName,
        requestedQuantity: Number(l.requestedQuantity),
        requiredDate: l.requiredDate || null,
      })),
    }),
    onSuccess: (created) => {
      toast.success(`PR ${created.requisitionNumber} created.`);
      queryClient.invalidateQueries({ queryKey: ["purchase-requisitions"] });
      navigate("/purchase-orders");
    },
    onError: (e) =>
      toast.error(`Create failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  function setLine(idx: number, patch: Partial<DraftLine>) {
    setLines(prev => prev.map((l, i) => (i === idx ? { ...l, ...patch } : l)));
  }

  function pickProduct(idx: number, productId: string) {
    const product = sourceableProducts.find(p => p.productId === productId);
    if (!product) {
      setLine(idx, { productId: "", productSku: "", productName: "" });
      return;
    }
    setLine(idx, {
      productId: product.productId,
      productSku: product.sku,
      productName: product.name,
    });
  }

  function addLine() {
    setLines(prev => [...prev, blankLine()]);
  }

  function removeLine(idx: number) {
    setLines(prev => prev.length === 1 ? prev : prev.filter((_, i) => i !== idx));
  }

  const linesValid = lines.every(l =>
    l.productId.length > 0 && Number(l.requestedQuantity) > 0
  );
  const canSave = requisitionNumber.trim().length > 0 && linesValid && !mutation.isPending;

  return (
    <>
      <PageHeader
        title="New purchase requisition"
        trail={[
          { label: "Purchasing" },
          { label: "Requisitions" },
          { label: "New" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/purchase-orders")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="purchasing_clerk"
              disabled={!canSave}
              onClick={() => mutation.mutate()}
            >
              Create
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6 space-y-4">
        <div className="grid gap-4 lg:grid-cols-2 max-w-4xl">
          <FormSection title="Header">
            <Field label="Requisition number" required>
              <input
                type="text"
                value={requisitionNumber}
                onChange={(e) => setRequisitionNumber(e.target.value)}
                placeholder="PR-2026-0001"
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Requested by">
              <input
                type="text"
                value={requestedBy}
                onChange={(e) => setRequestedBy(e.target.value)}
                placeholder="Tom (purchasing_clerk)"
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>
        </div>

        <FormSection title="Lines">
          <div className="mb-3 flex justify-end">
            <ActionButton icon={<Plus className="h-4 w-4" />} onClick={addLine}>
              Add line
            </ActionButton>
          </div>
          <div className="overflow-hidden rounded-md border border-border-default">
            <table className="w-full text-sm">
              <thead className="bg-bg-elevated text-xs text-text-secondary">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">Product</th>
                  <th className="px-3 py-2 text-right font-medium" style={{ width: 120 }}>Quantity</th>
                  <th className="px-3 py-2 text-left font-medium" style={{ width: 160 }}>Required by</th>
                  <th className="px-3 py-2" style={{ width: 60 }} />
                </tr>
              </thead>
              <tbody>
                {lines.map((l, idx) => (
                  <tr key={l.key} className="border-t border-border-default">
                    <td className="px-3 py-2">
                      <Select
                        value={l.productId}
                        onChange={(e) => pickProduct(idx, e.target.value)}
                      >
                        <option value="">— select product —</option>
                        {sourceableProducts.map(p => (
                          <option key={p.productId} value={p.productId}>
                            {p.sku} — {p.name}
                          </option>
                        ))}
                      </Select>
                    </td>
                    <td className="px-3 py-2">
                      <NumberInput
                        min="0"
                        step="1"
                        value={l.requestedQuantity}
                        onChange={(e) => setLine(idx, { requestedQuantity: e.target.value })}
                      />
                    </td>
                    <td className="px-3 py-2">
                      <input
                        type="date"
                        value={l.requiredDate}
                        onChange={(e) => setLine(idx, { requiredDate: e.target.value })}
                        className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
                      />
                    </td>
                    <td className="px-3 py-2 text-center">
                      <button
                        type="button"
                        onClick={() => removeLine(idx)}
                        disabled={lines.length === 1}
                        className="text-text-muted hover:text-status-error disabled:opacity-30 disabled:cursor-not-allowed"
                        title="Remove line"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </FormSection>
      </div>
    </>
  );
}

function blankLine(): DraftLine {
  return {
    key: Math.random().toString(36).slice(2),
    productId: "",
    productSku: "",
    productName: "",
    requestedQuantity: "1",
    requiredDate: "",
  };
}
