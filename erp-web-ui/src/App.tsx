import { Route, Routes, Navigate } from "react-router-dom";
import { AppShell } from "./components/layout/AppShell";
import { Home } from "./routes/Home";
import { SalesOrders } from "./routes/sales/SalesOrders";
import { SalesOrderNew } from "./routes/sales/SalesOrderNew";
import { SalesOrderDetail } from "./routes/sales/SalesOrderDetail";
import { Customers } from "./routes/sales/Customers";
import { CustomerDetail } from "./routes/sales/CustomerDetail";
import { CustomerNew } from "./routes/sales/CustomerNew";
import { PendingReview } from "./routes/finance/PendingReview";
import { SupplierInvoices } from "./routes/finance/SupplierInvoices";
import { SupplierInvoiceNew } from "./routes/finance/SupplierInvoiceNew";
import { SupplierInvoiceDetail } from "./routes/finance/SupplierInvoiceDetail";
import { JournalEntries } from "./routes/finance/JournalEntries";
import { ExchangeRate } from "./routes/finance/ExchangeRate";
import { CustomerInvoices } from "./routes/finance/CustomerInvoices";
import { Payments } from "./routes/finance/Payments";
import { PaymentNew } from "./routes/finance/PaymentNew";
import { ArAp } from "./routes/finance/ArAp";
import { Products } from "./routes/product/Products";
import { ProductNew } from "./routes/product/ProductNew";
import { ProductDetail } from "./routes/product/ProductDetail";
import { PurchaseOrders } from "./routes/purchasing/PurchaseOrders";
import { PurchaseOrderDetail } from "./routes/purchasing/PurchaseOrderDetail";
import { SupplierPrices } from "./routes/purchasing/SupplierPrices";
import { Suppliers } from "./routes/purchasing/Suppliers";
import { SupplierNew } from "./routes/purchasing/SupplierNew";
import { SupplierDetail } from "./routes/purchasing/SupplierDetail";
import { PurchaseRequisitionNew } from "./routes/purchasing/PurchaseRequisitionNew";
import { StockItems } from "./routes/inventory/StockItems";
import { StockAdjustmentNew } from "./routes/inventory/StockAdjustmentNew";
import { StockReservations } from "./routes/inventory/StockReservations";
import { GoodsReceipts } from "./routes/inventory/GoodsReceipts";
import { GoodsReceiptNew } from "./routes/inventory/GoodsReceiptNew";
import { Shipments } from "./routes/inventory/Shipments";
import { ShipmentNew } from "./routes/inventory/ShipmentNew";
import { StockMovements } from "./routes/inventory/StockMovements";
import { WorkOrders } from "./routes/manufacturing/WorkOrders";
import { WorkOrderDetail } from "./routes/manufacturing/WorkOrderDetail";
import { ProductionBoard } from "./routes/manufacturing/ProductionBoard";
import { Boms } from "./routes/manufacturing/Boms";
import { AuditLog } from "./routes/system/AuditLog";
import { Users } from "./routes/system/Users";
import { Atp } from "./routes/reporting/Atp";
import { MaterialShortages } from "./routes/reporting/MaterialShortages";
import { FinancialDashboard } from "./routes/reporting/FinancialDashboard";
import { PurchaseOrderTracking } from "./routes/reporting/PurchaseOrderTracking";

/**
 * This shell ships:
 *   - the full module-grouped sidebar with placeholders for every page
 *   - the Sales Orders list as the one fully wired route (look-and-feel
 *     reference for the remaining pages)
 *
 * Remaining pages replace placeholders in this order:
 *   - Manufacturing (work orders + production board write actions)
 *   - Finance (pending review, journal viewer + reverse, payments)
 *   - Purchasing + Product authoring
 *   - Sales cancel + cross-cutting polish
 */
export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Home />} />

        {/* ---- Sales ---- */}
        <Route path="/sales-orders"      element={<SalesOrders />} />
        <Route path="/sales-orders/new"  element={<SalesOrderNew />} />
        <Route path="/sales-orders/:id"  element={<SalesOrderDetail />} />
        <Route path="/customers"         element={<Customers />} />
        <Route path="/customers/new"     element={<CustomerNew />} />
        <Route path="/customers/:id"     element={<CustomerDetail />} />

        {/* ---- Purchasing ---- */}
        <Route path="/suppliers"               element={<Suppliers />} />
        <Route path="/suppliers/new"           element={<SupplierNew />} />
        <Route path="/suppliers/:id"           element={<SupplierDetail />} />
        <Route path="/purchase-requisitions"   element={<PurchaseRequisitionNew />} />
        <Route path="/purchase-orders"         element={<PurchaseOrders />} />
        <Route path="/purchase-orders/:id"     element={<PurchaseOrderDetail />} />
        <Route path="/purchase-orders/tracking" element={<PurchaseOrderTracking />} />
        <Route path="/supplier-prices"         element={<SupplierPrices />} />

        {/* ---- Inventory ---- */}
        <Route path="/stock-items"          element={<StockItems />} />
        <Route path="/stock-adjustments/new" element={<StockAdjustmentNew />} />
        <Route path="/stock-reservations"   element={<StockReservations />} />
        <Route path="/goods-receipts"       element={<GoodsReceipts />} />
        <Route path="/goods-receipts/new"   element={<GoodsReceiptNew />} />
        <Route path="/shipments"            element={<Shipments />} />
        <Route path="/shipments/new"        element={<ShipmentNew />} />
        <Route path="/stock-movements"      element={<StockMovements />} />

        {/* ---- Manufacturing ---- */}
        <Route path="/work-orders"          element={<WorkOrders />} />
        <Route path="/work-orders/:id"      element={<WorkOrderDetail />} />
        <Route path="/production-board"     element={<ProductionBoard />} />
        <Route path="/boms"                 element={<Boms />} />

        {/* ---- Finance ---- */}
        <Route path="/supplier-invoices"                element={<SupplierInvoices />} />
        <Route path="/supplier-invoices/new"            element={<SupplierInvoiceNew />} />
        <Route path="/supplier-invoices/pending-review" element={<PendingReview />} />
        <Route path="/supplier-invoices/:id"            element={<SupplierInvoiceDetail />} />
        <Route path="/customer-invoices"                element={<CustomerInvoices />} />
        <Route path="/payments"                         element={<Payments />} />
        <Route path="/payments/new"                     element={<PaymentNew />} />
        <Route path="/journal-entries"                  element={<JournalEntries />} />
        <Route path="/exchange-rate"                    element={<ExchangeRate />} />
        <Route path="/ar-ap"                            element={<ArAp />} />

        {/* ---- Reporting ---- */}
        <Route path="/atp"                  element={<Atp />} />
        <Route path="/material-shortages"   element={<MaterialShortages />} />
        <Route path="/financial-dashboard"  element={<FinancialDashboard />} />

        {/* ---- Products (Catalog) ---- */}
        <Route path="/products"             element={<Products />} />
        <Route path="/products/new"         element={<ProductNew />} />
        <Route path="/products/:id"         element={<ProductDetail />} />

        {/* ---- System ---- */}
        <Route path="/system/users"         element={<Users />} />
        <Route path="/system/audit-log"     element={<AuditLog />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
