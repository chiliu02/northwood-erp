import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Save, X } from "lucide-react";
import { apiGet, apiPost, ApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { PageHeader } from "@/components/ui/PageHeader";
import { ActionButton } from "@/components/ui/ActionButton";
import { FormSection, Field } from "@/components/ui/FormSection";

const DEFAULT_WAREHOUSE = "MAIN";

interface Product {
  productId: string;
  sku: string;
  name: string;
  status: string;
}

interface StockBalance {
  warehouseId: string;
  productId: string;
  onHand: string;
  reserved: string;
  available: string;
}

interface CreatedAdjustment {
  id: string;
}

type Mode = "DELTA" | "SET";

/** Post a manual stock adjustment. Warehouse-manager path (§2.29). */
export function StockAdjustmentNew() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: () => apiGet<Product[]>("/api/products"),
  });
  const adjustable = (products ?? []).filter((p) => p.status === "active");

  const [adjustmentNumber, setAdjustmentNumber] = useState(() => `ADJ-${Date.now()}`);
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [productId, setProductId] = useState("");
  const [mode, setMode] = useState<Mode>("DELTA");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");

  const product = adjustable.find((p) => p.productId === productId);

  const { data: balance } = useQuery({
    queryKey: ["stock-balance", productId, warehouseCode],
    queryFn: () => apiGet<StockBalance>(
      `/api/stock-adjustments/balance?productId=${productId}&warehouseCode=${encodeURIComponent(warehouseCode)}`),
    enabled: !!productId && warehouseCode.trim().length > 0,
  });

  const onHand = Number(balance?.onHand ?? 0);
  const reserved = Number(balance?.reserved ?? 0);
  const available = balance ? Number(balance.available) : onHand - reserved;

  const valueNum = Number(value);
  const valueValid = value.trim().length > 0 && !Number.isNaN(valueNum);
  const afterOnHand = mode === "SET" ? valueNum : onHand + valueNum;
  const afterReserved = reserved; // an adjustment never touches reserved
  const afterAvailable = afterOnHand - afterReserved;
  const delta = afterOnHand - onHand;

  const negativeTarget = mode === "SET" && valueValid && valueNum < 0;
  const breachesReserved = valueValid && afterOnHand < reserved; // also catches afterOnHand < 0
  const noChange = valueValid && delta === 0;

  const canSave =
    !!productId
    && reason.trim().length > 0
    && adjustmentNumber.trim().length > 0
    && valueValid
    && !negativeTarget
    && !breachesReserved
    && !noChange;

  const mutation = useMutation({
    mutationFn: () => apiPost<CreatedAdjustment>("/api/stock-adjustments", {
      adjustmentNumber: adjustmentNumber.trim(),
      productId,
      productSku: product?.sku ?? "",
      productName: product?.name ?? "",
      warehouseCode,
      mode,
      value: value.trim(),
      reason: reason.trim(),
    }),
    onSuccess: () => {
      toast.success(`Stock adjustment ${adjustmentNumber.trim()} posted.`);
      queryClient.invalidateQueries({ queryKey: ["stock-balance", productId, warehouseCode] });
      queryClient.invalidateQueries({ queryKey: ["stock-items"] });
      navigate("/stock-items");
    },
    onError: (e) =>
      toast.error(`Adjustment failed: ${e instanceof ApiError ? e.message : String(e)}`),
  });

  return (
    <>
      <PageHeader
        title="Post stock adjustment"
        trail={[
          { label: "Home", to: "/" },
          { label: "Inventory" },
          { label: "Stock Balances", to: "/stock-items" },
          { label: "Adjust" },
        ]}
        actions={
          <>
            <ActionButton icon={<X className="h-4 w-4" />} onClick={() => navigate("/stock-items")}>
              Cancel
            </ActionButton>
            <ActionButton
              variant="primary"
              icon={<Save className="h-4 w-4" />}
              requiresRole="warehouse_manager"
              disabled={!canSave || mutation.isPending}
              onClick={() => mutation.mutate()}
            >
              Post adjustment
            </ActionButton>
          </>
        }
      />

      <div className="px-8 py-6">
        <p className="mb-4 max-w-3xl text-sm text-text-muted">
          Adjust on-hand stock for a single product (cycle-count correction, damage, demo setup).
          Inventory records the delta as a <code>stock_movement</code> and bumps{" "}
          <code>stock_balance.on_hand</code>; finance posts the inventory gain/loss to the GL
          (account 5400) valued at standard cost. Reserved quantity is never touched.
        </p>

        <div className="grid gap-4 max-w-4xl lg:grid-cols-2">
          <FormSection title="Adjustment">
            <Field label="Adjustment number" required>
              <input
                type="text"
                value={adjustmentNumber}
                onChange={(e) => setAdjustmentNumber(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Warehouse code" required>
              <input
                type="text"
                value={warehouseCode}
                onChange={(e) => setWarehouseCode(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Product" required>
              <select
                value={productId}
                onChange={(e) => setProductId(e.target.value)}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              >
                <option value="">— pick a product —</option>
                {adjustable.map((p) => (
                  <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
                ))}
              </select>
            </Field>
            <Field label="Mode" required hint={mode === "DELTA" ? "signed change (+/-)" : "target on-hand quantity"}>
              <div role="radiogroup" aria-label="Adjustment mode"
                   className="inline-flex rounded-md border border-border-default bg-bg-surface p-0.5">
                {(["DELTA", "SET"] as Mode[]).map((m) => (
                  <button
                    key={m}
                    type="button"
                    role="radio"
                    aria-checked={mode === m}
                    onClick={() => setMode(m)}
                    className={
                      "flex items-center gap-1.5 whitespace-nowrap rounded px-3 py-1.5 text-xs font-medium transition-colors " +
                      (mode === m
                        ? "bg-bg-elevated text-text-primary shadow-sm"
                        : "text-text-muted hover:text-text-primary")
                    }
                  >
                    {m === "DELTA" ? "Adjust by ±N" : "Set to N"}
                  </button>
                ))}
              </div>
            </Field>
            <Field label={mode === "DELTA" ? "Change (±)" : "Target on-hand"} required>
              <input
                type="number" step="0.0001"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder={mode === "DELTA" ? "e.g. -5 or 25" : "e.g. 500"}
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-right text-sm tabular-nums focus:border-border-focus focus:outline-none"
              />
            </Field>
            <Field label="Reason" required>
              <input
                type="text"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="cycle count / damage / demo setup"
                className="h-9 w-full rounded-md border border-border-default bg-bg-surface px-3 text-sm focus:border-border-focus focus:outline-none"
              />
            </Field>
          </FormSection>

          <FormSection title="Balance preview">
            {!productId ? (
              <p className="text-sm text-text-faint">Pick a product to see its current balance.</p>
            ) : (
              <>
                <table className="w-full text-sm">
                  <thead className="border-b border-border-default text-left text-[11px] uppercase tracking-wider text-text-muted">
                    <tr>
                      <th className="py-2 pr-3 font-semibold"></th>
                      <th className="py-2 pr-3 text-right font-semibold">Current</th>
                      <th className="py-2 text-right font-semibold">After</th>
                    </tr>
                  </thead>
                  <tbody className="tabular-nums">
                    <PreviewRow label="On hand" current={onHand} after={valueValid ? afterOnHand : null} changed />
                    <PreviewRow label="Reserved" current={reserved} after={valueValid ? afterReserved : null} />
                    <PreviewRow label="Available" current={available} after={valueValid ? afterAvailable : null} changed />
                  </tbody>
                </table>
                {valueValid && !negativeTarget && !breachesReserved && !noChange && (
                  <p className="mt-3 text-xs text-text-muted">
                    {delta > 0 ? "Inventory gain" : "Inventory loss"} of{" "}
                    <span className="font-medium tabular-nums">{fmt(Math.abs(delta))}</span> — posts{" "}
                    {delta > 0 ? "Dr inventory / Cr 5400" : "Dr 5400 / Cr inventory"}.
                  </p>
                )}
                {negativeTarget && <Warn>Target on-hand cannot be negative.</Warn>}
                {breachesReserved && !negativeTarget && (
                  <Warn>After-adjustment on-hand ({fmt(afterOnHand)}) would fall below the reserved quantity ({fmt(reserved)}).</Warn>
                )}
                {noChange && !breachesReserved && <Warn>No change — on-hand is already {fmt(onHand)}.</Warn>}
              </>
            )}
          </FormSection>
        </div>
      </div>
    </>
  );
}

function PreviewRow({ label, current, after, changed }: {
  label: string; current: number; after: number | null; changed?: boolean;
}) {
  const moved = after != null && after !== current;
  return (
    <tr className="border-b border-border-default last:border-b-0">
      <td className="py-2 pr-3 text-text-muted">{label}</td>
      <td className="py-2 pr-3 text-right">{fmt(current)}</td>
      <td className={`py-2 text-right ${changed && moved ? "font-semibold" : "text-text-muted"}`}>
        {after == null ? "—" : fmt(after)}
      </td>
    </tr>
  );
}

function Warn({ children }: { children: React.ReactNode }) {
  return (
    <p className="mt-3 rounded-md border border-status-error/30 bg-status-error-soft px-3 py-2 text-xs text-status-error">
      {children}
    </p>
  );
}

function fmt(n: number): string {
  return n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}
