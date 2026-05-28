// Wire types mirroring Java records on the reporting / product / inventory side.
// BigDecimal serialises to string in Spring Boot's default Jackson config, so
// every monetary field below is `string`. Number() to convert when needed.

export interface SalesOrder360 {
  salesOrderHeaderId: string;
  orderNumber: string;
  customerId: string;
  customerName: string | null;
  orderDate: string | null;            // ISO date
  requestedDeliveryDate: string | null;
  orderStatus: string;
  stockStatus: string | null;
  manufacturingStatus: string | null;
  shipmentStatus: string | null;
  invoiceStatus: string | null;
  paymentStatus: string | null;
  paymentTerms: string;                // 'on_shipment' | 'prepayment'
  currencyCode: string;
  totalAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  hasShortage: boolean;
  shortageSummary: string | null;
  lastEventType: string | null;
  lastEventAt: string | null;          // ISO instant
  updatedAt: string | null;
}

export interface PurchaseOrderTracking {
  purchaseOrderHeaderId: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierName: string | null;
  poStatus: string;
  orderDate: string | null;
  expectedReceiptDate: string | null;
  currencyCode: string;
  orderedAmount: string;
  receivedAmount: string;
  invoicedAmount: string;
  paidAmount: string;
  outstandingAmount: string;
  receiptStatus: string | null;
  invoiceStatus: string | null;
  paymentStatus: string | null;
  matchStatus: string | null;
  lastGoodsReceiptHeaderId: string | null;
  lastSupplierInvoiceHeaderId: string | null;
  lastPaymentId: string | null;
  updatedAt: string | null;
}

export interface ProductionPlanningRow {
  workOrderId: string;
  workOrderNumber: string;
  salesOrderHeaderId: string | null;
  orderNumber: string | null;
  finishedProductId: string;
  finishedProductSku: string;
  finishedProductName: string;
  plannedQuantity: string;
  completedQuantity: string;
  workOrderStatus: string;
  materialStatus: string | null;
  shortageMaterialsCount: number;
  shortageSummary: string | null;
  openPurchaseOrdersCount: number;
  expectedMaterialAvailableDate: string | null;
  plannedStartDate: string | null;
  plannedEndDate: string | null;
  priority: string | null;
  updatedAt: string | null;
}

export interface AvailableToPromiseRow {
  productId: string;
  productSku: string;
  productName: string;
  onHandQuantity: string;
  reservedForSales: string;
  reservedForProduction: string;
  availableQuantity: string;
  incomingFromProduction: string;
  incomingFromPurchase: string;
  earliestAvailableDate: string | null;
  stockStatus: string;
  updatedAt: string | null;
}

export interface MaterialShortageRow {
  materialProductId: string;
  materialSku: string;
  materialName: string;
  requiredQuantity: string;
  availableQuantity: string;
  shortageQuantity: string;
  affectedWorkOrdersCount: number;
  affectedSalesOrdersCount: number;
  openPurchaseOrdersCount: number;
  incomingPurchaseQuantity: string;
  expectedReceiptDate: string | null;
  status: string;
  updatedAt: string | null;
}

export interface ProductRow {
  productId: string;
  sku: string;
  name: string;
  description: string | null;
  productType: string;
  salesPrice: string;
  standardCost: string;
  reorderPoint: string;
  reorderQuantity: string;
  valuationClass: string | null;
  activeBomId: string | null;
  status: string;
  version: number;
}

export interface BomNode {
  componentProductId: string;
  componentSku: string;
  componentName: string;
  componentKind: string;
  quantityPerFinishedUnit: string;
  scrapFactorPercent: string;
  subBomHeaderId: string | null;
  children: BomNode[];
}

export interface BomTree {
  bomHeaderId: string;
  productId: string;
  productSku: string;
  productName: string;
  components: BomNode[];
}

export interface BomFlatComponent {
  componentProductId: string;
  componentSku: string;
  componentName: string;
  componentKind: string;
  cumulativeQuantityPerFinishedUnit: string;
}

export interface StockItemRow {
  stockItemId: string;
  productId: string;
  productSku: string;
  productName: string;
  productType: string;
  baseUomCode: string;
  trackingMode: string;
  reorderPoint: string;
  reorderQuantity: string;
  onHand: string;
  reserved: string;
  available: string;
  version: number;
}

export interface StockBalanceRow {
  warehouseId: string;
  productId: string;
  onHand: string;
  reserved: string;
  available: string;
}

// Purchasing — purchase-order detail (header + lines), from purchasing-service GET /{id}.

export interface PurchaseOrderLineView {
  lineId: string;
  lineNumber: number;
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice: string;
  lineTotal: string;
  status: string;
}

export interface PurchaseOrderView {
  id: string;
  purchaseOrderNumber: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  purchaseRequisitionHeaderId: string | null;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  version: number;
  lines: PurchaseOrderLineView[];
}

// Sales — sales-order detail (header + lines), from sales-service GET /{id}.
// Distinct from SalesOrder360 above which is the reporting-side projection.

export interface SalesOrderLineView {
  lineId: string;
  lineNumber: number;
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  reservedQuantity: string;
  unitPrice: string;
  lineTotal: string;
  lineStatus: string;
}

export interface SalesOrderView {
  id: string;
  orderNumber: string;
  customerId: string;
  customerCode: string | null;
  customerName: string;
  orderDate: string | null;
  requestedDeliveryDate: string | null;
  status: string;
  paymentTerms: string;                // 'on_shipment' | 'prepayment'
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  version: number;
  lines: SalesOrderLineView[];
}

// Manufacturing — work order detail (header + materials + operations)

export interface WorkOrderOperationView {
  id: string;
  operationSequence: number;
  operationCode: string;
  description: string | null;
  workCenterId: string;
  plannedSetupMinutes: string;
  plannedRunMinutes: string;
  status: string;             // 'planned' | 'in_progress' | 'completed' | 'skipped'
  actualMinutes: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface WorkOrderMaterialView {
  id: string;
  componentProductId: string;
  componentSku: string;
  componentName: string;
  requiredQuantity: string;
  unitCost: string;
  status: string;
}

export interface WorkOrderView {
  workOrderId: string;
  workOrderNumber: string;
  salesOrderHeaderId: string | null;
  salesOrderLineId: string | null;
  parentWorkOrderId: string | null;
  finishedProductId: string;
  finishedProductSku: string;
  finishedProductName: string;
  bomHeaderId: string;
  plannedQuantity: string;
  status: string;
  materialStatus: string | null;
  completedQuantity: string;
  actualStartAt: string | null;
  actualCompletedAt: string | null;
  version: number;
  materials: WorkOrderMaterialView[];
  operations: WorkOrderOperationView[];
}

// Finance — customer invoices

export interface CustomerInvoiceLineView {
  lineId: string;
  lineNumber: number;
  salesOrderLineId: string | null;
  productId: string;
  productSku: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  taxRate: string;
  taxAmount: string;
  lineTotal: string;
}

export interface CustomerInvoiceView {
  id: string;
  invoiceNumber: string;
  salesOrderHeaderId: string;
  customerId: string;
  customerCode: string | null;
  customerName: string;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  version: number;
  lines: CustomerInvoiceLineView[];
}

// Finance — supplier invoices

export interface SupplierInvoiceLineView {
  lineId: string;
  lineNumber: number;
  purchaseOrderLineId: string;
  goodsReceiptLineId: string | null;
  productId: string;
  productSku: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  taxRate: string;
  taxAmount: string;
  lineTotal: string;
}

export interface SupplierInvoiceView {
  id: string;
  internalInvoiceNumber: string;
  supplierInvoiceNumber: string;
  purchaseOrderHeaderId: string;
  goodsReceiptHeaderId: string | null;
  supplierId: string;
  supplierName: string;
  currencyCode: string;
  subtotalAmount: string;
  taxAmount: string;
  totalAmount: string;
  status: string;
  matchStatus: string | null;
  version: number;
  lines: SupplierInvoiceLineView[];
}

// Inventory — goods receipts (header + optional lines, depending on endpoint)

export interface GoodsReceiptLineView {
  lineId: string;
  purchaseOrderLineId: string | null;
  productId: string;
  productSku: string;
  productName: string;
  receivedQuantity: string;
  unitCost: string;
  lineCost: string;
}

export interface GoodsReceiptView {
  id: string;
  goodsReceiptNumber: string;
  purchaseOrderHeaderId: string;
  supplierId: string;
  supplierName: string | null;
  warehouseId: string;
  warehouseCode: string;
  status: string;
  lines: GoodsReceiptLineView[];
  version: number;
}

// Finance — journal entries

export interface JournalEntryLineView {
  lineId: string;
  lineNumber: number;
  glAccountId: string;
  accountCode: string;
  accountName: string;
  debitAmount: string;
  creditAmount: string;
  description: string;
  postingDate: string;
}

export interface JournalEntryView {
  journalEntryHeaderId: string;
  journalNumber: string;
  postingDate: string;
  sourceModule: string;
  sourceDocumentType: string;
  sourceDocumentId: string;
  description: string;
  status: string;
  currencyCode: string;
  exchangeRate: string;
  exchangeRateCapturedAt: string | null;
  lines: JournalEntryLineView[];
  version: number;
}

export interface JournalEntrySummary {
  journalEntryHeaderId: string;
  journalNumber: string;
  postingDate: string;
  sourceModule: string;
  sourceDocumentType: string;
  sourceDocumentId: string;
  description: string;
  status: string;
  currencyCode: string;
  totalAmount: string;
  lineCount: number;
  postedAt: string | null;
}

// Purchasing — requisitions, suppliers, prices

export interface PurchaseRequisitionLineView {
  lineId: string;
  lineNumber: number;
  productId: string;
  productSku: string;
  productName: string;
  requestedQuantity: string;
  requiredDate: string | null;
  suggestedSupplierId: string;
  suggestedSupplierName: string;
  status: string;
}

export interface PurchaseRequisitionView {
  id: string;
  requisitionNumber: string;
  sourceType: string;            // "manual" | "work_order_shortage"
  sourceWorkOrderId: string | null;
  sourceProductId: string | null;
  status: string;
  requestedBy: string;
  version: number;
  lines: PurchaseRequisitionLineView[];
}

export interface SupplierView {
  supplierId: string;
  supplierCode: string;
  name: string;
  status: string;
}

export interface SupplierPriceView {
  supplierProductPriceId: string;
  supplierId: string;
  productId: string;
  currencyCode: string;
  unitPrice: string;
  version: number;
}
