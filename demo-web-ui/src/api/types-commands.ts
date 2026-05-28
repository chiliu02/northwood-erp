// Wire shapes for command-side payloads. Mirror the *Request records on the
// Java side. BigDecimal fields are sent as strings (Spring Boot's default
// Jackson config accepts both number and string for BigDecimal).

export interface PlaceOrderRequest {
  orderNumber: string;
  customerCode: string;
  requestedDeliveryDate?: string | null;     // ISO date
  currencyCode: string;
  /** 'on_shipment' | 'prepayment'. Omit to inherit the customer's default. */
  paymentTerms?: string | null;
  lines: PlaceOrderLine[];
}
export interface PlaceOrderLine {
  productId: string;
  productSku: string;
  productName: string;
  orderedQuantity: string;
  unitPrice?: string;
  taxRate?: string;
}

export interface AdjustStockRequest {
  adjustmentNumber: string;
  productId: string;
  productSku: string;
  productName: string;
  warehouseCode: string;
  mode: "DELTA" | "SET";
  value: string;
  reason: string;
}

export interface PostGoodsReceiptRequest {
  goodsReceiptNumber: string;
  purchaseOrderHeaderId: string;
  supplierId?: string;
  supplierName?: string;
  warehouseCode: string;
  lines: PostGoodsReceiptLine[];
}
export interface PostGoodsReceiptLine {
  purchaseOrderLineId?: string;
  productId: string;
  productSku: string;
  productName: string;
  receivedQuantity: string;
  unitCost?: string;
}

export interface PostShipmentRequest {
  shipmentNumber: string;
  salesOrderHeaderId: string;
  customerId?: string;
  customerName?: string;
  warehouseCode: string;
  lines: PostShipmentLine[];
}
export interface PostShipmentLine {
  salesOrderLineId?: string;
  productId: string;
  productSku: string;
  productName: string;
  shippedQuantity: string;
  unitCost?: string;
}

export interface RecordSupplierInvoiceRequest {
  internalInvoiceNumber: string;
  supplierInvoiceNumber: string;
  purchaseOrderHeaderId: string;
  goodsReceiptHeaderId?: string;
  supplierId: string;
  supplierCode?: string;
  supplierName: string;
  currencyCode: string;
  lines: RecordSupplierInvoiceLine[];
}
export interface RecordSupplierInvoiceLine {
  purchaseOrderLineId: string;
  goodsReceiptLineId?: string;
  productId: string;
  productSku: string;
  productName: string;
  quantity: string;
  unitPrice: string;
  taxRate?: string;
}

export type PaymentMethod = "bank_transfer" | "cash" | "card" | "cheque";

export interface RecordSupplierPaymentRequest {
  paymentNumber: string;
  supplierInvoiceHeaderId: string;
  amount: string;
  paymentMethod: PaymentMethod;
  paymentDate?: string;       // ISO date
}

export interface RecordCustomerPaymentRequest {
  paymentNumber: string;
  customerInvoiceHeaderId: string;
  amount: string;
  paymentMethod: PaymentMethod;
  paymentDate?: string;
}

export interface CompleteOperationRequest {
  actualMinutes: string;
}

export interface ChangeSalesPriceRequest {
  salesPrice: string;
  currencyCode: string;
}

export interface ChangeStandardCostRequest {
  standardCost: string;
  currencyCode: string;
}

export interface SetReorderPolicyRequest {
  reorderPoint: string;
  reorderQuantity: string;
}

export interface ChangeMakeVsBuyRequest {
  isPurchased: boolean;
  isManufactured: boolean;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description?: string | null;
  productType: string;
  baseUomId: string;
  salesPrice: string;
  standardCost: string;
  currencyCode: string;
}

export interface CancelSalesOrderRequest {
  reason: string;
}

export interface ApprovePurchaseOrderRequest {
  approver: string;
  reason: string;
}
