import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchProducts, fetchStockBalance } from "@/api/fetchers";
import { adjustStock } from "@/api/commands";
import { Button, FieldRow, FormCard, FormStatus, Input, PersonaTag, Select, type SubmitState } from "@/components/ui/Form";
import { PERSONAS } from "@/personas";

const DEFAULT_WAREHOUSE = "MAIN";
type Mode = "DELTA" | "SET";

export function StockAdjustments() {
  const persona = PERSONAS.mike;
  const { data: products } = useQuery({ queryKey: ["products"], queryFn: fetchProducts });
  const adjustable = (products ?? []).filter((p) => p.status === "active");

  const [adjustmentNumber, setAdjustmentNumber] = useState(() => `ADJ-${Date.now()}`);
  const [warehouseCode, setWarehouseCode] = useState(DEFAULT_WAREHOUSE);
  const [productId, setProductId] = useState("");
  const [mode, setMode] = useState<Mode>("DELTA");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  const product = adjustable.find((p) => p.productId === productId);

  const { data: balance } = useQuery({
    queryKey: ["stock-balance", productId, warehouseCode],
    queryFn: () => fetchStockBalance(productId, warehouseCode),
    enabled: !!productId && warehouseCode.trim().length > 0,
  });

  const onHand = Number(balance?.onHand ?? 0);
  const reserved = Number(balance?.reserved ?? 0);
  const available = balance ? Number(balance.available) : onHand - reserved;

  const valueNum = Number(value);
  const valueValid = value.trim().length > 0 && !Number.isNaN(valueNum);
  const afterOnHand = mode === "SET" ? valueNum : onHand + valueNum;
  const afterAvailable = afterOnHand - reserved;
  const delta = afterOnHand - onHand;
  const negativeTarget = mode === "SET" && valueValid && valueNum < 0;
  const breachesReserved = valueValid && afterOnHand < reserved;
  const noChange = valueValid && delta === 0;

  const canSubmit =
    !!productId && reason.trim().length > 0 && valueValid
    && !negativeTarget && !breachesReserved && !noChange && submit.status !== "submitting";

  async function onSubmit() {
    if (!product) return;
    setSubmit({ status: "submitting" });
    try {
      const result = await adjustStock({
        adjustmentNumber,
        productId,
        productSku: product.sku,
        productName: product.name,
        warehouseCode,
        mode,
        value: value.trim(),
        reason: reason.trim(),
      });
      setSubmit({ status: "success", message: `posted ${result.id ?? adjustmentNumber}` });
      setAdjustmentNumber(`ADJ-${Date.now()}`);
      setValue("");
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <FormCard title="Stock adjustment" persona={<PersonaTag {...persona} />}>
      <p className="mb-4 text-sm text-text-muted">
        Adjust on-hand stock for a single product without a purchase order — cycle-count
        correction, damage, or demo setup. Inventory records the delta as a{" "}
        <span className="font-mono">stock_movement</span> and bumps{" "}
        <span className="font-mono">stock_balance.on_hand</span>; finance posts the inventory
        gain/loss to the GL (account 5400) valued at standard cost. Reserved is never touched.
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <FieldRow label="Adjustment number" required>
          <Input value={adjustmentNumber} onChange={(e) => setAdjustmentNumber(e.target.value)} />
        </FieldRow>
        <FieldRow label="Product" required>
          <Select value={productId} onChange={(e) => setProductId(e.target.value)}>
            <option value="">— pick a product —</option>
            {adjustable.map((p) => (
              <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
            ))}
          </Select>
        </FieldRow>
        <FieldRow label="Warehouse code" required>
          <Input value={warehouseCode} onChange={(e) => setWarehouseCode(e.target.value)} />
        </FieldRow>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-3">
        <FieldRow label="Mode" required hint={mode === "DELTA" ? "signed change (+/-)" : "target on-hand quantity"}>
          <Select value={mode} onChange={(e) => setMode(e.target.value as Mode)}>
            <option value="DELTA">Adjust by ±N</option>
            <option value="SET">Set to N</option>
          </Select>
        </FieldRow>
        <FieldRow label={mode === "DELTA" ? "Change (±)" : "Target on-hand"} required>
          <Input
            type="number" step="0.0001" className="text-right"
            value={value}
            placeholder={mode === "DELTA" ? "e.g. -5 or 25" : "e.g. 500"}
            onChange={(e) => setValue(e.target.value)}
          />
        </FieldRow>
        <FieldRow label="Reason" required>
          <Input
            value={reason}
            placeholder="cycle count / damage / demo setup"
            onChange={(e) => setReason(e.target.value)}
          />
        </FieldRow>
      </div>

      {productId && (
        <div className="mt-6">
          <h2 className="mb-2 text-sm font-semibold uppercase tracking-wider text-text-muted">Balance preview</h2>
          <table className="w-full max-w-md text-sm">
            <thead className="border-b border-border-subtle text-left text-[11px] uppercase tracking-wider text-text-muted">
              <tr>
                <th className="py-2 pr-3 font-semibold"></th>
                <th className="py-2 pr-3 text-right font-semibold">Current</th>
                <th className="py-2 text-right font-semibold">After</th>
              </tr>
            </thead>
            <tbody className="tabular-nums">
              <PreviewRow label="On hand" current={onHand} after={valueValid ? afterOnHand : null} highlight />
              <PreviewRow label="Reserved" current={reserved} after={valueValid ? reserved : null} />
              <PreviewRow label="Available" current={available} after={valueValid ? afterAvailable : null} highlight />
            </tbody>
          </table>
          {valueValid && negativeTarget && <Hint>⚠ Target on-hand cannot be negative.</Hint>}
          {valueValid && breachesReserved && !negativeTarget && (
            <Hint>⚠ After-adjustment on-hand ({fmt(afterOnHand)}) would fall below reserved ({fmt(reserved)}).</Hint>
          )}
          {valueValid && noChange && !breachesReserved && <Hint>⚠ No change — on-hand is already {fmt(onHand)}.</Hint>}
          {valueValid && !negativeTarget && !breachesReserved && !noChange && (
            <Hint>
              {delta > 0 ? "Inventory gain" : "Inventory loss"} of {fmt(Math.abs(delta))} — posts{" "}
              {delta > 0 ? "Dr inventory / Cr 5400" : "Dr 5400 / Cr inventory"}.
            </Hint>
          )}
        </div>
      )}

      <div className="mt-6 flex items-center justify-end gap-3">
        <FormStatus state={submit} />
        <Button variant="primary" onClick={onSubmit} disabled={!canSubmit}>
          Post adjustment
        </Button>
      </div>
    </FormCard>
  );
}

function PreviewRow({ label, current, after, highlight }: {
  label: string; current: number; after: number | null; highlight?: boolean;
}) {
  const moved = after != null && after !== current;
  return (
    <tr className="border-b border-border-subtle last:border-b-0">
      <td className="py-2 pr-3 text-text-muted">{label}</td>
      <td className="py-2 pr-3 text-right">{fmt(current)}</td>
      <td className={`py-2 text-right ${highlight && moved ? "font-semibold" : "text-text-muted"}`}>
        {after == null ? "—" : fmt(after)}
      </td>
    </tr>
  );
}

function Hint({ children }: { children: React.ReactNode }) {
  return <p className="mt-2 text-xs text-text-muted">{children}</p>;
}

function fmt(n: number): string {
  return n.toLocaleString("en-AU", { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}
