import { Route, Routes, Navigate } from "react-router-dom";
import { AppShell } from "./components/layout/AppShell";
import { Dashboard } from "./routes/Dashboard";
import { SalesOrders } from "./routes/SalesOrders";
import { PurchaseOrders } from "./routes/PurchaseOrders";
import { ProductionBoard } from "./routes/ProductionBoard";
import { Atp } from "./routes/Atp";
import { MaterialShortages } from "./routes/MaterialShortages";
import { StockItems } from "./routes/StockItems";
import { Products } from "./routes/Products";
import { SagaConsole } from "./routes/SagaConsole";
import { PlaceOrder } from "./routes/PlaceOrder";
import { GoodsReceipts } from "./routes/GoodsReceipts";
import { StockAdjustments } from "./routes/StockAdjustments";
import { Shipments } from "./routes/Shipments";
import { SupplierInvoices } from "./routes/SupplierInvoices";
import { Payments } from "./routes/Payments";
import { CustomerInvoices } from "./routes/CustomerInvoices";
import { PendingReview } from "./routes/PendingReview";
import { JournalEntries } from "./routes/JournalEntries";
import { ReverseJournal } from "./routes/ReverseJournal";
import { PurchaseRequisitions } from "./routes/PurchaseRequisitions";
import { SupplierPrices } from "./routes/SupplierPrices";
import { EventLog } from "./routes/EventLog";
import { Boms } from "./routes/Boms";

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Dashboard />} />

        {/* Phase 2 — read-only depth */}
        <Route path="/products"            element={<Products />} />
        <Route path="/sales-orders"        element={<SalesOrders />} />
        <Route path="/sales-orders/:id"    element={<SalesOrders />} />
        <Route path="/stock-items"         element={<StockItems />} />
        <Route path="/atp"                 element={<Atp />} />
        <Route path="/production-board"    element={<ProductionBoard />} />
        <Route path="/work-orders"         element={<ProductionBoard />} />
        <Route path="/material-shortages"  element={<MaterialShortages />} />
        <Route path="/purchase-orders"     element={<PurchaseOrders />} />
        <Route path="/purchase-orders/tracking" element={<PurchaseOrders />} />

        {/* Phase 4 — write paths */}
        <Route path="/sales-orders/new"   element={<PlaceOrder />} />
        <Route path="/goods-receipts"     element={<GoodsReceipts />} />
        <Route path="/stock-adjustments"  element={<StockAdjustments />} />
        <Route path="/shipments"          element={<Shipments />} />
        <Route path="/supplier-invoices"  element={<SupplierInvoices />} />
        <Route path="/payments"           element={<Payments />} />

        {/* Emma — pricing/reorder/discontinue are inline modals on /products
            (no dedicated routes). The legacy /products/pricing and
            /products/reorder URLs redirect home so old bookmarks still load
            cleanly; the sidebar links to them were dropped in §1B.8. */}
        <Route path="/products/pricing" element={<Navigate to="/products" replace />} />
        <Route path="/products/reorder" element={<Navigate to="/products" replace />} />
        <Route path="/boms"             element={<Boms />} />

        {/* Tom */}
        <Route path="/purchase-requisitions" element={<PurchaseRequisitions />} />
        <Route path="/supplier-prices"       element={<SupplierPrices />} />

        {/* Olivia */}
        <Route path="/customer-invoices" element={<CustomerInvoices />} />
        <Route path="/supplier-invoices/pending-review" element={<PendingReview />} />

        {/* Daniel */}
        <Route path="/journal-entries"   element={<JournalEntries />} />
        <Route path="/journal-entries/reverse" element={<ReverseJournal />} />

        {/* Cross-cutting — Phase 3 */}
        <Route path="/saga-console"      element={<SagaConsole />} />
        <Route path="/event-log"         element={<EventLog />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
