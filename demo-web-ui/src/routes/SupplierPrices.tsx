import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { fetchProducts, fetchSupplierPrices, fetchSuppliers } from "@/api/fetchers";
import { setSupplierProductPrice } from "@/api/commands";
import type { ProductRow, SupplierPriceView, SupplierView } from "@/api/types";
import { Button, FieldRow, FormStatus, Input, Select, type SubmitState } from "@/components/ui/Form";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge, inferStatusKind } from "@/components/ui/StatusBadge";
import { formatMoney, truncateUuid } from "@/lib/utils";
import { PERSONAS } from "@/personas";

export function SupplierPrices() {
  const persona = PERSONAS.tom;
  const { data: suppliers, isLoading: suppliersLoading } = useQuery({
    queryKey: ["suppliers"],
    queryFn: fetchSuppliers,
  });
  const { data: products } = useQuery({
    queryKey: ["products"],
    queryFn: fetchProducts,
  });

  const supplierList: SupplierView[] = (suppliers ?? []).filter((s) => s.status === "active");
  const [supplierId, setSupplierId] = useState("");

  // Auto-select first supplier on load.
  if (!supplierId && supplierList[0]) {
    queueMicrotask(() => setSupplierId(supplierList[0].supplierId));
  }

  const supplier = supplierList.find((s) => s.supplierId === supplierId);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[28px] font-semibold tracking-tight">Supplier prices</h1>
        <span className="flex items-center gap-2 text-sm text-text-muted">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: persona.accentVar }} aria-hidden />
          {persona.name} · {persona.role}
        </span>
      </div>

      <p className="text-sm text-text-muted">
        Authoring surface for <span className="font-mono">purchasing.supplier_product_price</span>.
        Setting a price emits <span className="font-mono">purchasing.SupplierProductPriceChanged</span>;
        the manufacturing materials-cost projection picks it up via inbox and flows it through every
        BoM that references the product.
      </p>

      <div className="flex items-center gap-3">
        <Select
          value={supplierId}
          onChange={(e) => setSupplierId(e.target.value)}
          className="max-w-md"
          disabled={suppliersLoading}
        >
          {supplierList.length === 0 && <option>No active suppliers</option>}
          {supplierList.map((s) => (
            <option key={s.supplierId} value={s.supplierId}>
              {s.supplierCode} · {s.name}
            </option>
          ))}
        </Select>
        {supplier && <NewPriceButton supplier={supplier} products={products ?? []} />}
      </div>

      {supplierId && <PriceTable supplierId={supplierId} products={products ?? []} />}
    </div>
  );
}

function PriceTable({ supplierId, products }: { supplierId: string; products: ProductRow[] }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["supplier-prices", supplierId],
    queryFn: () => fetchSupplierPrices(supplierId),
    refetchInterval: 5_000,
  });
  const list = data ?? [];
  const productById = new Map(products.map((p) => [p.productId, p]));

  return (
    <>
      {error && (
        <div
          className="rounded-md border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--color-state-error)",
            background: "color-mix(in srgb, var(--color-state-error) 12%, transparent)",
          }}
        >
          <p className="font-medium" style={{ color: "var(--color-state-error)" }}>
            Couldn't reach purchasing-service on :8085
          </p>
          <p className="mt-1 text-text-muted">{String(error)}</p>
        </div>
      )}
      <div className="overflow-hidden rounded-lg border border-border-subtle bg-bg-elevated">
        <table className="w-full text-sm">
          <thead className="border-b border-border-subtle bg-bg-base/50 text-left text-[11px] uppercase tracking-wider text-text-muted">
            <tr>
              <th className="px-3 py-2 font-semibold">SKU</th>
              <th className="px-3 py-2 font-semibold">Product</th>
              <th className="px-3 py-2 font-semibold">Currency</th>
              <th className="px-3 py-2 text-right font-semibold">Unit price</th>
              <th className="px-3 py-2 font-semibold"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {list.map((row) => {
              const p = productById.get(row.productId);
              return (
                <tr key={row.supplierProductPriceId} className="hover:bg-bg-hover">
                  <td className="px-3 py-2 font-mono">{p?.sku ?? truncateUuid(row.productId)}</td>
                  <td className="px-3 py-2">{p?.name ?? "—"}</td>
                  <td className="px-3 py-2">
                    <StatusBadge kind={inferStatusKind(null)}>{row.currencyCode}</StatusBadge>
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">
                    {formatMoney(row.unitPrice, row.currencyCode)}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <EditPriceButton supplierId={supplierId} row={row} product={p} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {!isLoading && list.length === 0 && !error && (
          <p className="px-4 py-6 text-center text-sm text-text-faint">
            No prices set for this supplier yet. Use "set price" to add one.
          </p>
        )}
      </div>
    </>
  );
}

function NewPriceButton({ supplier, products }: { supplier: SupplierView; products: ProductRow[] }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="primary" onClick={() => setOpen(true)}>
        + set price
      </Button>
      <PriceModal
        open={open}
        onClose={() => setOpen(false)}
        supplier={supplier}
        products={products}
        initialProductId=""
        initialUnitPrice=""
        initialCurrency="AUD"
      />
    </>
  );
}

function EditPriceButton({ supplierId, row, product }: {
  supplierId: string;
  row: SupplierPriceView;
  product: ProductRow | undefined;
}) {
  const [open, setOpen] = useState(false);
  const supplier: SupplierView = { supplierId, supplierCode: "", name: "", status: "active" };
  return (
    <>
      <Button variant="ghost" onClick={() => setOpen(true)}>
        <Pencil className="h-3.5 w-3.5" />
      </Button>
      <PriceModal
        open={open}
        onClose={() => setOpen(false)}
        supplier={supplier}
        products={product ? [product] : []}
        initialProductId={row.productId}
        initialUnitPrice={row.unitPrice}
        initialCurrency={row.currencyCode}
        title={product ? `Edit price for ${product.sku}` : "Edit price"}
      />
    </>
  );
}

function PriceModal({ open, onClose, supplier, products, initialProductId, initialUnitPrice, initialCurrency, title }: {
  open: boolean;
  onClose: () => void;
  supplier: SupplierView;
  products: ProductRow[];
  initialProductId: string;
  initialUnitPrice: string;
  initialCurrency: string;
  title?: string;
}) {
  const queryClient = useQueryClient();
  const [productId, setProductId] = useState(initialProductId);
  const [unitPrice, setUnitPrice] = useState(initialUnitPrice);
  const [currencyCode, setCurrencyCode] = useState(initialCurrency);
  const [submit, setSubmit] = useState<SubmitState>({ status: "idle" });

  async function onSubmit() {
    setSubmit({ status: "submitting" });
    try {
      await setSupplierProductPrice({
        supplierId: supplier.supplierId,
        productId,
        currencyCode,
        unitPrice,
      });
      setSubmit({ status: "success", message: "price saved" });
      queryClient.invalidateQueries({ queryKey: ["supplier-prices", supplier.supplierId] });
      setTimeout(() => { setSubmit({ status: "idle" }); onClose(); }, 800);
    } catch (e) {
      setSubmit({ status: "error", message: String(e) });
    }
  }

  return (
    <Modal
      open={open}
      onClose={() => { setSubmit({ status: "idle" }); onClose(); }}
      title={title ?? `Set price for ${supplier.name || supplier.supplierCode}`}
    >
      <div className="space-y-4">
        <FieldRow label="Product" required>
          <Select value={productId} onChange={(e) => setProductId(e.target.value)} disabled={!!initialProductId}>
            <option value="">— pick a product —</option>
            {products.map((p) => (
              <option key={p.productId} value={p.productId}>{p.sku} · {p.name}</option>
            ))}
          </Select>
        </FieldRow>
        <FieldRow label="Currency" required>
          <Select value={currencyCode} onChange={(e) => setCurrencyCode(e.target.value)}>
            <option value="AUD">AUD</option>
            <option value="USD">USD</option>
            <option value="EUR">EUR</option>
          </Select>
        </FieldRow>
        <FieldRow label="Unit price" required>
          <Input
            type="number" step="0.01" min="0"
            value={unitPrice}
            onChange={(e) => setUnitPrice(e.target.value)}
          />
        </FieldRow>
        <div className="flex items-center justify-end gap-3">
          <FormStatus state={submit} />
          <Button
            variant="primary"
            onClick={onSubmit}
            disabled={submit.status === "submitting" || !productId || !unitPrice}
          >
            Save price
          </Button>
        </div>
        <p className="text-xs text-text-faint">
          Emits <span className="font-mono">purchasing.SupplierProductPriceChanged</span>; the
          manufacturing materials-cost projection consumes it and updates BoM rollups.
        </p>
      </div>
    </Modal>
  );
}
