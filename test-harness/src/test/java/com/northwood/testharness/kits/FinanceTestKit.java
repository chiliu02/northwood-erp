package com.northwood.testharness.kits;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.PaymentService;
import com.northwood.finance.application.SupplierInvoiceService;
import com.northwood.finance.application.inbox.GoodsReceivedHandler;
import com.northwood.finance.application.inbox.ProductCreatedHandler;
import com.northwood.finance.application.inbox.ProductDiscontinuedHandler;
import com.northwood.finance.application.inbox.PurchaseOrderCreatedHandler;
import com.northwood.finance.application.inbox.SalesOrderShippedHandler;
import com.northwood.finance.application.inbox.ShipmentPostedCogsHandler;
import com.northwood.finance.application.inbox.StandardCostChangedHandler;
import com.northwood.finance.application.inbox.ValuationClassChangedHandler;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.finance.InMemoryCustomerInvoiceRepository;
import com.northwood.testharness.inmemory.finance.InMemoryGlAccountLookup;
import com.northwood.testharness.inmemory.finance.InMemoryJournalEntryRepository;
import com.northwood.testharness.inmemory.finance.InMemoryPaymentRepository;
import com.northwood.testharness.inmemory.finance.InMemoryProductCard;
import com.northwood.testharness.inmemory.finance.InMemoryPurchaseOrderLineFactsProjection;
import com.northwood.testharness.inmemory.finance.InMemorySupplierInvoiceRepository;
import java.math.BigDecimal;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-service test composition for finance. Wires
 * {@link CustomerInvoiceService}, {@link PaymentService},
 * {@link SupplierInvoiceService}, {@link JournalEntryService} (real impl
 * over an in-memory journal repository + GL chart) and registers the 8
 * finance inbox handlers.
 *
 * <p>Finance has no sagas; the only state carried in the kit is the
 * inbox-driven projections (PurchaseOrderLineFacts, ProductCard —
 * consolidated standard-cost + valuation-class + discontinued-at) plus the
 * four aggregate repositories.
 *
 * <p>The {@code maintain_allocation_totals} DB trigger isn't modelled in
 * memory; tests that exercise the partial-payment path call
 * {@link InMemoryCustomerInvoiceRepository#recordAllocation} /
 * {@link InMemorySupplierInvoiceRepository#recordAllocation} as the
 * stand-in.
 */
public final class FinanceTestKit {

    public final InMemoryOutboxPort outbox = new InMemoryOutboxPort();
    public final InMemoryInboxPort inbox = new InMemoryInboxPort();

    public final InMemoryCustomerInvoiceRepository customerInvoices;
    public final InMemorySupplierInvoiceRepository supplierInvoices;
    public final InMemoryPaymentRepository payments;
    public final InMemoryJournalEntryRepository journalEntries = new InMemoryJournalEntryRepository();
    public final InMemoryGlAccountLookup glAccounts = new InMemoryGlAccountLookup();
    public final InMemoryProductCard productCards = new InMemoryProductCard();
    public final InMemoryPurchaseOrderLineFactsProjection purchaseOrderLineFacts = new InMemoryPurchaseOrderLineFactsProjection();

    public final JournalEntryService journalService;
    public final CustomerInvoiceService customerInvoiceService;
    public final SupplierInvoiceService supplierInvoiceService;
    public final PaymentService paymentService;

    public FinanceTestKit(SynchronousBus bus, ObjectMapper json) {
        this.customerInvoices = new InMemoryCustomerInvoiceRepository(outbox, json);
        this.supplierInvoices = new InMemorySupplierInvoiceRepository(outbox, json);
        this.payments = new InMemoryPaymentRepository(outbox, json);

        // Tests in the harness exercise posting/reverse paths only; the list-side
        // CQRS query port isn't driven, so an empty-stub satisfies the constructor.
        this.journalService = new JournalEntryService(
            journalEntries,
            (limit, sourceDocumentType) -> java.util.List.of(),
            glAccounts,
            productCards
        );
        this.customerInvoiceService = new CustomerInvoiceService(customerInvoices, journalService);
        this.supplierInvoiceService = new SupplierInvoiceService(
            supplierInvoices, purchaseOrderLineFacts, journalService, new BigDecimal("2.0")
        );
        this.paymentService = new PaymentService(
            payments, supplierInvoices, customerInvoices, journalService
        );

        bus.register(outbox);
        bus.register(new SalesOrderShippedHandler(inbox, customerInvoiceService, json));
        bus.register(new ShipmentPostedCogsHandler(inbox, journalService, productCards, json));
        bus.register(new GoodsReceivedHandler(inbox, purchaseOrderLineFacts, journalService, json));
        bus.register(new PurchaseOrderCreatedHandler(inbox, purchaseOrderLineFacts, json));
        bus.register(new ProductCreatedHandler(inbox, productCards, json));
        bus.register(new ProductDiscontinuedHandler(inbox, productCards, json));
        bus.register(new StandardCostChangedHandler(inbox, productCards, json));
        bus.register(new ValuationClassChangedHandler(inbox, productCards, json));
    }
}
